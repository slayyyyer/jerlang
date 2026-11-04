package org.jerlang.compiler;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.jerlang.compiler.codegen.Codegen;
import org.jerlang.compiler.codegen.CodegenException;
import org.jerlang.compiler.lint.Diagnostic;
import org.jerlang.compiler.lint.Linter;
import org.jerlang.compiler.parse.Parser;
import org.jerlang.compiler.parse.Parser.ParseException;
import org.jerlang.compiler.preprocess.PreprocessException;
import org.jerlang.compiler.preprocess.Preprocessor;
import org.jerlang.compiler.scan.ScanException;
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
     * caller owns the output. A lint error, a failed parse transform, or a
     * warning when warnings are errors, raises {@link CompileException}.
     */
    public static byte[] compile(Path source, Options options) throws IOException {
        List<Term> forms = parse(source, options);
        try {
            forms = ParseTransforms.apply(forms, options);
        } catch (ParseTransformException error) {
            throw new CompileException(source, Collections.singletonList(
                Diagnostic.error(error.line(), error.getMessage())));
        }
        List<Diagnostic> diagnostics = lint(forms, options);
        if (fails(diagnostics, options)) {
            throw new CompileException(source, diagnostics);
        }
        try {
            return Codegen.compile(forms);
        } catch (CodegenException error) {
            throw compileError(source, 1, error.getMessage());
        }
    }

    /**
     * Compile one source and write {@code Module.beam} into the output
     * directory. Failures are returned in the result rather than thrown, so a
     * command can print every diagnostic and continue with the next file.
     */
    public static Result compileFile(Path source, Options options) throws IOException {
        try {
            List<Term> forms = parse(source, options);
            try {
                forms = ParseTransforms.apply(forms, options);
            } catch (ParseTransformException error) {
                return new Result(null, null, Collections.singletonList(
                    Diagnostic.error(error.line(), error.getMessage())));
            }
            Atom module = moduleName(source, forms);
            List<Diagnostic> diagnostics = lint(forms, options);
            if (fails(diagnostics, options)) {
                return new Result(module, null, diagnostics);
            }
            byte[] bytes;
            try {
                bytes = Codegen.compile(forms);
            } catch (CodegenException error) {
                return new Result(module, null, Collections.singletonList(
                    Diagnostic.error(1, error.getMessage())));
            }
            Path directory = options.outputDirectory().toAbsolutePath().normalize();
            Files.createDirectories(directory);
            Path beam = directory.resolve(module.toString() + ".beam");
            writeAtomically(directory, beam, bytes);
            return new Result(module, beam, diagnostics);
        } catch (CompileException error) {
            return new Result(null, null, error.diagnostics());
        }
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

    private static List<Term> parse(Path source, Options options) throws CompileException {
        Path absolute = source.toAbsolutePath().normalize();
        try {
            return Parser.parseForms(Preprocessor.scanFile(absolute,
                options.includePath(), options.libraryPath(), options.otpRelease(),
                options.defines()));
        } catch (PreprocessException error) {
            throw compileError(source, error.line(), error.getMessage());
        } catch (ScanException error) {
            throw compileError(source, error.line(), error.getMessage());
        } catch (ParseException error) {
            throw compileError(source, error.line(), error.getMessage());
        } catch (IOException error) {
            throw compileError(source, 1, error.getMessage());
        }
    }

    /**
     * The beam file is named after the -module attribute, not the source file,
     * which is also what erlc does.
     */
    private static Atom moduleName(Path source, List<Term> forms) throws CompileException {
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
        throw compileError(source, 1, "no -module attribute");
    }

    private static CompileException compileError(Path source, int line, String message) {
        return new CompileException(source, Collections.singletonList(
            Diagnostic.error(line, message)));
    }

    /**
     * A beam is visible only after its complete contents are in place. Writing
     * the temporary file beside the target keeps the final move atomic on the
     * same filesystem, and a failed build can never truncate a good beam from
     * an earlier compile.
     */
    private static void writeAtomically(Path directory, Path beam, byte[] bytes)
            throws IOException {
        Path temporary = Files.createTempFile(directory,
            "." + beam.getFileName().toString() + "-", ".tmp");
        try {
            Files.write(temporary, bytes);
            try {
                Files.move(temporary, beam, StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException error) {
                Files.move(temporary, beam, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temporary);
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
        private final boolean debugInfo;
        private final long parseTransformTimeoutMillis;
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
            this.debugInfo = builder.debugInfo;
            this.parseTransformTimeoutMillis = builder.parseTransformTimeoutMillis;
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

        /**
         * Whether +debug_info was requested. The compiler always emits line
         * tables, but the flag is visible to parse transforms in their option
         * list, as it is under OTP.
         */
        public boolean debugInfo() {
            return debugInfo;
        }

        public long parseTransformTimeoutMillis() {
            return parseTransformTimeoutMillis;
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
            private boolean debugInfo;
            private long parseTransformTimeoutMillis = 30_000L;
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
             * A library root for -include_lib and parse-transform beams. It is
             * the directory that holds the app-version directories, not an
             * ebin directory; a transform may also sit directly in it or in an
             * ebin directory below it.
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

            public Builder debugInfo(boolean debugInfo) {
                this.debugInfo = debugInfo;
                return this;
            }

            /**
             * Bound a parse transform that has to spawn an Erlang process. The
             * default is 30 seconds; zero disables the bound, matching the
             * historical behaviour of Module.apply/2.
             */
            public Builder parseTransformTimeoutMillis(long timeoutMillis) {
                if (timeoutMillis < 0) {
                    throw new IllegalArgumentException(
                        "parse transform timeout must not be negative");
                }
                this.parseTransformTimeoutMillis = timeoutMillis;
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
     * A lint error, a parse-transform failure, or a warning treated as one
     * stopped the compilation.
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
