package org.jerlang.compiler;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

import org.jerlang.compiler.codegen.CodegenException;
import org.jerlang.compiler.lint.Diagnostic;
import org.jerlang.compiler.preprocess.PreprocessException;
import org.jerlang.compiler.scan.ScanException;

/**
 * = The compile command
 *
 * `jerlang compile` is the command-line surface of {@link Compiler}. The
 * runnable jar dispatches to it when its first argument is `compile`; it never
 * invokes `erlc`, so a machine needs only a JVM to build a module that does not
 * include OTP headers.
 *
 * ----
 * java -jar jerlang.jar compile -o build -I include -DDEBUG hello.erl
 * ----
 *
 * The exit status is 0 when every source compiled, 1 when a source failed, and
 * 2 for a malformed command line.
 */
public final class CompilerCommand {

    private static final String USAGE = String.join(System.lineSeparator(),
        "Usage: jerlang compile [options] file.erl ...",
        "",
        "Options:",
        "  -o DIR              write beam files to DIR (default: .)",
        "  -I DIR              add DIR to the -include search path",
        "  -pa DIR             add DIR as a library root for -include_lib",
        "  -Dname[=value]      define a macro (without a value: true)",
        "  -W                  lint and print warnings",
        "  -W0                 disable warnings",
        "  -Werror             treat warnings as errors",
        "  +debug_info         accepted; line tables are already emitted",
        "  +warnings_as_errors treat warnings as errors",
        "  --                  end of options",
        "",
        "The output directory is created when it does not exist.");

    private CompilerCommand() {
    }

    public static void main(String[] args) {
        System.exit(run(args));
    }

    public static int run(String[] args) {
        return run(args, System.out, System.err);
    }

    /**
     * Parse and run a command line. The streams are parameters so the tests
     * can inspect diagnostics without replacing the process output.
     */
    public static int run(String[] args, PrintStream out, PrintStream err) {
        Compiler.Options.Builder builder = Compiler.Options.builder();
        List<Path> sources = new ArrayList<>();
        try {
            for (int index = 0; index < args.length; index++) {
                String argument = args[index];
                switch (argument) {
                case "--":
                    for (int rest = index + 1; rest < args.length; rest++) {
                        sources.add(Paths.get(args[rest]));
                    }
                    index = args.length;
                    break;
                case "-h":
                case "--help":
                    out.println(USAGE);
                    return 0;
                case "-o":
                    if (index + 1 == args.length) {
                        return missing("-o", err);
                    }
                    builder.outputDirectory(Paths.get(args[++index]));
                    break;
                case "-I":
                    if (index + 1 == args.length) {
                        return missing("-I", err);
                    }
                    builder.includePath(args[++index]);
                    break;
                case "-pa":
                    if (index + 1 == args.length) {
                        return missing("-pa", err);
                    }
                    builder.libraryPath(args[++index]);
                    break;
                case "-D":
                    if (index + 1 == args.length) {
                        return missing("-D", err);
                    }
                    if (define(args[++index], builder, err) != 0) {
                        return 2;
                    }
                    break;
                case "-W":
                    builder.warnings(true);
                    break;
                case "-W0":
                    builder.warnings(false);
                    break;
                case "-Werror":
                    builder.warnings(true);
                    builder.warningsAsErrors(true);
                    break;
                default:
                    if (argument.startsWith("-I") && argument.length() > 2) {
                        builder.includePath(argument.substring(2));
                    } else if (argument.startsWith("-pa") && argument.length() > 3) {
                        builder.libraryPath(argument.substring(3));
                    } else if (argument.startsWith("-D") && argument.length() > 2) {
                        if (define(argument.substring(2), builder, err) != 0) {
                            return 2;
                        }
                    } else if (argument.startsWith("+")) {
                        int status = compilerOption(argument, builder, err);
                        if (status != 0) {
                            return status;
                        }
                    } else if (argument.startsWith("-") && argument.length() > 1) {
                        err.println("jerlang compile: unknown option " + argument);
                        return 2;
                    } else {
                        sources.add(Paths.get(argument));
                    }
                    break;
                }
            }

            if (sources.isEmpty()) {
                err.println(USAGE);
                return 2;
            }

            Compiler.Options options = builder.build();
            int status = 0;
            for (Path source : sources) {
                try {
                    Compiler.Result result = Compiler.compileFile(source, options);
                    print(source, result.diagnostics(), err);
                    if (!result.success()) {
                        if (options.warningsAsErrors() && !hasError(result.diagnostics())) {
                            err.println(source + ": warnings being treated as errors");
                        }
                        status = 1;
                    }
                } catch (PreprocessException error) {
                    err.println(error.getMessage());
                    status = 1;
                } catch (ScanException error) {
                    err.println(source + ": " + error.getMessage());
                    status = 1;
                } catch (CodegenException error) {
                    err.println(source + ": " + error.getMessage());
                    status = 1;
                } catch (IOException error) {
                    err.println(source + ": " + error.getMessage());
                    status = 1;
                } catch (RuntimeException error) {
                    err.println(source + ": " + error.getMessage());
                    status = 1;
                }
            }
            return status;
        } catch (IllegalArgumentException error) {
            err.println("jerlang compile: " + error.getMessage());
            return 2;
        }
    }

    /**
     * epp's -Dname expands to true, and -Dname=value scans the value as Erlang
     * tokens, so -DLEVEL=2 defines an integer and -DNAME=worker an atom.
     */
    private static int define(String definition, Compiler.Options.Builder builder,
            PrintStream err) {
        int equals = definition.indexOf('=');
        String name = equals < 0 ? definition : definition.substring(0, equals);
        if (name.isEmpty()) {
            err.println("jerlang compile: -D needs a macro name");
            return 2;
        }
        builder.define(name, equals < 0 ? null : definition.substring(equals + 1));
        return 0;
    }

    /**
     * A +term is a compiler option, not an emulator option. Only the options
     * this front end can honour are accepted; an unknown one is an error
     * rather than a silently ignored flag.
     */
    private static int compilerOption(String option, Compiler.Options.Builder builder,
            PrintStream err) {
        switch (option.substring(1)) {
        case "debug_info":
            // Line tables are part of every beam; the option is accepted so a
            // build script written for erlc keeps working.
            return 0;
        case "warnings_as_errors":
            builder.warnings(true);
            builder.warningsAsErrors(true);
            return 0;
        default:
            err.println("jerlang compile: unsupported compiler option " + option);
            return 2;
        }
    }

    private static boolean hasError(List<Diagnostic> diagnostics) {
        for (Diagnostic diagnostic : diagnostics) {
            if (diagnostic.isError()) {
                return true;
            }
        }
        return false;
    }

    private static void print(Path source, List<Diagnostic> diagnostics, PrintStream err) {
        for (Diagnostic diagnostic : diagnostics) {
            err.println(source + ":" + diagnostic.format());
        }
    }

    private static int missing(String option, PrintStream err) {
        err.println("jerlang compile: " + option + " needs a value");
        return 2;
    }

}
