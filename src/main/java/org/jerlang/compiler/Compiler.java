package org.jerlang.compiler;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.jerlang.compiler.codegen.Codegen;
import org.jerlang.compiler.lint.Diagnostic;
import org.jerlang.compiler.lint.Linter;
import org.jerlang.compiler.parse.Parser;
import org.jerlang.compiler.preprocess.Preprocessor;
import org.jerlang.type.Atom;
import org.jerlang.type.Term;
import org.jerlang.type.Tuple;

/**
 * = The compiler
 *
 * The public entry point for turning an Erlang source file into a BEAM file.
 * It uses the same scanner, preprocessor, parser, linter and code generator as
 * the tests, so the command line and an embedding program cannot disagree about
 * what a source means.
 *
 * ----
 * Compiler.Options options = Compiler.Options.builder()
 *     .outputDirectory(Paths.get("build"))
 *     .includePath("include")
 *     .define("DEBUG", null)
 *     .warnings(true)
 *     .build();
 * Compiler.Result result = Compiler.compileFile(Paths.get("hello.erl"), options);
 * ----
 *
 * Linting is opt-in because it is a separate pass: without {@code warnings}
 * the compiler only reports what it cannot parse or lower. With it, lint errors
 * stop the build and lint warnings are reported alongside the beam. A caller
 * that wants bytes without a file uses {@link #compile(Path, Options)}.
 */
public final class Compiler {

    /**
     * The OTP release whose preprocessor macros and loader conventions were
     * targeted. OTP 29 is what the corpus and the boot tests use.
     */
    public static final int DEFAULT_OTP_RELEASE = 29;

    private Compiler() {
    }

    /**
     * Compile one source and return the BEAM bytes. Nothing is written; the
     * caller owns the output. A lint error, or a warning when warnings are
     * errors, raises {@link CompileException}.
     */
    public static byte[] compile(Path source, Options options) throws IOException {
        Parsed parsed = parse(source, options);
        List<Diagnostic> diagnostics = lint(parsed.forms, options);
        if (fails(diagnostics, options)) {
            throw new CompileException(source, diagnostics);
        }
        return Codegen.compile(parsed.forms);
    }

    /**
     * Compile one source and write {@code Module.beam} into the output
     * directory. Failures are returned in the result rather than thrown, so a
     * command can print every diagnostic and continue with the next file.
     */
    public static Result compileFile(Path source, Options options) throws IOException {
        Parsed parsed = parse(source, options);
        List<Diagnostic> diagnostics = lint(parsed.forms, options);
        if (fails(diagnostics, options)) {
            return new Result(parsed.module, null, diagnostics);
        }
        Path directory = options.outputDirectory().toAbsolutePath().normalize();
        Files.createDirectories(directory);
        Path beam = directory.resolve(parsed.module.toString() + ".beam");
        Files.write(beam, Codegen.compile(parsed.forms));
        return new Result(parsed.module, beam, diagnostics);
    }

    private static boolean fails(List<Diagnostic> diagnostics, Options options) {
        boolean warning = false;
        for (Diagnostic diagnostic : diagnostics) {
            if (diagnostic.isError()) {
                return true;
            }
            warning = true;
        }
        return warning && options.warningsAsErrors();
    }

    private static List<Diagnostic> lint(List<Term> forms, Options options) {
        if (!options.warnings()) {
            return Collections.emptyList();
        }
        return Linter.lint(forms);
    }

    private static Parsed parse(Path source, Options options) throws IOException {
        Path absolute = source.toAbsolutePath().normalize();
        List<Term> forms = Parser.parseForms(Preprocessor.scanFile(absolute,
            options.includePath(), options.libraryPath(), options.otpRelease(),
            options.defines()));
        return new Parsed(moduleName(absolute, forms), forms);
    }

    /**
     * The beam file is named after the -module attribute, not the source file,
     * which is also what erlc does.
     */
    private static Atom moduleName(Path source, List<Term> forms) throws IOException {
        for (Term form : forms) {
            if (!(form instanceof Tuple)) {
                continue;
            }
            Tuple tuple = (Tuple) form;
            if (tuple.arity() >= 4
                && tuple.element(1) instanceof Atom
                && "attribute".equals(tuple.element(1).toString())
                && tuple.element(3) instanceof Atom
                && "module".equals(tuple.element(3).toString())
                && tuple.element(4) instanceof Atom) {
                return (Atom) tuple.element(4);
            }
        }
        throw new IOException("no -module attribute in " + source);
    }

    private static final class Parsed {

        private final Atom module;
        private final List<Term> forms;

        private Parsed(Atom module, List<Term> forms) {
            this.module = module;
            this.forms = forms;
        }

    }

    /**
     * What the compiler needs to read a source and where the beam goes.
     */
    public static final class Options {

        private final List<String> includePath;
        private final List<String> libraryPath;
        private final Map<String, String> defines;
        private final Path outputDirectory;
        private final boolean warnings;
        private final boolean warningsAsErrors;
        private final int otpRelease;

        private Options(Builder builder) {
            this.includePath = Collections.unmodifiableList(
                new ArrayList<>(builder.includePath));
            this.libraryPath = Collections.unmodifiableList(
                new ArrayList<>(builder.libraryPath));
            this.defines = Collections.unmodifiableMap(
                new LinkedHashMap<>(builder.defines));
            this.outputDirectory = builder.outputDirectory;
            this.warnings = builder.warnings;
            this.warningsAsErrors = builder.warningsAsErrors;
            this.otpRelease = builder.otpRelease;
        }

        public static Options defaults() {
            return builder().build();
        }

        public static Builder builder() {
            return new Builder();
        }

        public List<String> includePath() {
            return includePath;
        }

        public List<String> libraryPath() {
            return libraryPath;
        }

        public Map<String, String> defines() {
            return defines;
        }

        public Path outputDirectory() {
            return outputDirectory;
        }

        public boolean warnings() {
            return warnings;
        }

        public boolean warningsAsErrors() {
            return warningsAsErrors;
        }

        public int otpRelease() {
            return otpRelease;
        }

        public static final class Builder {

            private final List<String> includePath = new ArrayList<>();
            private final List<String> libraryPath = new ArrayList<>();
            private final Map<String, String> defines = new LinkedHashMap<>();
            private Path outputDirectory = Paths.get(".");
            private boolean warnings;
            private boolean warningsAsErrors;
            private int otpRelease = DEFAULT_OTP_RELEASE;

            private Builder() {
            }

            public Builder includePath(String directory) {
                includePath.add(require(directory, "include path"));
                return this;
            }

            public Builder includePaths(List<String> directories) {
                for (String directory : directories) {
                    includePath(directory);
                }
                return this;
            }

            /**
             * A library root for -include_lib. It is the directory that holds
             * the app-version directories, not an ebin directory.
             */
            public Builder libraryPath(String root) {
                libraryPath.add(require(root, "library root"));
                return this;
            }

            public Builder libraryPaths(List<String> roots) {
                for (String root : roots) {
                    libraryPath(root);
                }
                return this;
            }

            /**
             * Define a preprocessor macro. A null value is epp's -Dname form
             * and expands to true; otherwise the value is scanned as Erlang
             * tokens, so "2", "worker" and "\"text\"" all mean what they do
             * on the erlc command line.
             */
            public Builder define(String name, String value) {
                defines.put(require(name, "macro name"), value);
                return this;
            }

            public Builder outputDirectory(Path directory) {
                if (directory == null) {
                    throw new IllegalArgumentException("output directory is null");
                }
                this.outputDirectory = directory;
                return this;
            }

            public Builder warnings(boolean warnings) {
                this.warnings = warnings;
                return this;
            }

            public Builder warningsAsErrors(boolean warningsAsErrors) {
                this.warningsAsErrors = warningsAsErrors;
                return this;
            }

            public Builder otpRelease(int otpRelease) {
                if (otpRelease <= 0) {
                    throw new IllegalArgumentException(
                        "OTP release must be positive: " + otpRelease);
                }
                this.otpRelease = otpRelease;
                return this;
            }

            public Options build() {
                return new Options(this);
            }

            private static String require(String value, String what) {
                if (value == null || value.isEmpty()) {
                    throw new IllegalArgumentException(what + " is empty");
                }
                return value;
            }
        }

    }

    /**
     * The outcome of compiling one source. {@link #beam()} is null when the
     * build failed, and {@link #diagnostics()} explains why.
     */
    public static final class Result {

        private final Atom module;
        private final Path beam;
        private final List<Diagnostic> diagnostics;

        private Result(Atom module, Path beam, List<Diagnostic> diagnostics) {
            this.module = module;
            this.beam = beam;
            this.diagnostics = Collections.unmodifiableList(
                new ArrayList<>(diagnostics));
        }

        public Atom module() {
            return module;
        }

        public Path beam() {
            return beam;
        }

        public List<Diagnostic> diagnostics() {
            return diagnostics;
        }

        public boolean success() {
            return beam != null;
        }

    }

    /**
     * A lint error, or a warning treated as one, stopped the compilation.
     */
    public static final class CompileException extends IOException {

        private static final long serialVersionUID = 1L;

        private final List<Diagnostic> diagnostics;

        private CompileException(Path source, List<Diagnostic> diagnostics) {
            super(message(source, diagnostics));
            this.diagnostics = Collections.unmodifiableList(
                new ArrayList<>(diagnostics));
        }

        public List<Diagnostic> diagnostics() {
            return diagnostics;
        }

        private static String message(Path source, List<Diagnostic> diagnostics) {
            for (Diagnostic diagnostic : diagnostics) {
                if (diagnostic.isError()) {
                    return source + ":" + diagnostic.format();
                }
            }
            if (!diagnostics.isEmpty()) {
                return source + ":" + diagnostics.get(0).format();
            }
            return source + ": compilation failed";
        }

    }

}
