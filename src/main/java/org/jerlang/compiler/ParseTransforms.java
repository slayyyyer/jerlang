package org.jerlang.compiler;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

import org.jerlang.CodePaths;
import org.jerlang.FunctionSignature;
import org.jerlang.Module;
import org.jerlang.ModuleLoader;
import org.jerlang.ModuleRegistry;
import org.jerlang.compiler.scan.Scanner;
import org.jerlang.compiler.scan.Token;
import org.jerlang.type.Atom;
import org.jerlang.type.Integer;
import org.jerlang.type.Str;
import org.jerlang.type.Term;
import org.jerlang.type.Tuple;
import org.jerlang.vm.VirtualMachine;

/**
 * = Parse transforms
 *
 * A transform is an Erlang module named by {@code -compile({parse_transform,
 * Module})} or by an entry in a {@code -compile([...])} option list. It runs
 * after preprocessing and parsing and before linting or code generation, which
 * is the point where OTP runs it too.
 *
 * The transform receives the forms as an Erlang list and the compiler options
 * as a second list. The options are the OTP-shaped subset this front end can
 * honour: {@code debug_info}, {@code warnings_as_errors}, {@code {i, Dir}} and
 * {@code {d, Name, Value}}.
 *
 * The module is loaded from a {@code -pa} root, the working directory, or the
 * ordinary code path. A {@code -pa} root may be an ebin directory, an
 * application directory with an {@code ebin}, or a library root containing
 * versioned applications. The transform runs in a VM that the compiler starts
 * only when it is needed. The directory containing the transform is put on the
 * code path for the call, so helper modules beside it can be loaded.
 *
 * A spawned transform process is bounded by
 * {@link Compiler.Options#parseTransformTimeoutMillis()}. This is not an
 * operating-system sandbox: a transform is trusted code and can still call
 * {@code erlang:halt/1}, native code, or start work that outlives the compiler.
 */
final class ParseTransforms {

    private ParseTransforms() {
    }

    static List<Term> apply(List<Term> forms, Compiler.Options options) {
        List<Transform> transforms = collect(forms);
        if (transforms.isEmpty()) {
            return forms;
        }

        // Discover every module before starting a VM, so a missing transform
        // is a cheap, deterministic compile error rather than a scheduler
        // start followed by a shutdown.
        List<LoadedTransform> loaded = new ArrayList<>();
        for (Transform transform : transforms) {
            File file = find(transform.module, options);
            if (file == null) {
                throw new ParseTransformException(transform.line,
                    "parse transform " + transform.module
                        + " is not on the compiler path");
            }
            loaded.add(new LoadedTransform(transform, file));
        }

        boolean started = VirtualMachine.instance().startedAtMillis() == 0;
        if (started) {
            VirtualMachine.instance().start();
        }
        try {
            for (LoadedTransform transform : loaded) {
                transform.module = ModuleLoader.load(transform.transform.module,
                    transform.file);
                if (transform.module == null) {
                    throw new ParseTransformException(transform.transform.line,
                        "parse transform " + transform.transform.module
                            + " could not be loaded from " + transform.file);
                }
            }

            Term compilerOptions = compilerOptions(options);
            List<Term> current = forms;
            for (LoadedTransform transform : loaded) {
                current = run(transform, current, compilerOptions, options);
            }
            return current;
        } finally {
            if (started) {
                VirtualMachine.instance().shutdown();
            }
        }
    }

    private static List<Term> run(LoadedTransform transform, List<Term> forms,
            Term compilerOptions, Compiler.Options options) {
        synchronized (ParseTransforms.class) {
            List<String> addedPaths = addCodePath(transform.file);
            Module previous = ModuleRegistry.loaded(transform.transform.module);
            ModuleRegistry.register(transform.module);
            try {
                Term formsTerm = list(forms);
                Term params = org.jerlang.type.List.of(formsTerm, compilerOptions);
                Term result = transform.module.apply(new FunctionSignature(
                    transform.transform.module, Atom.of("parse_transform"),
                    Integer.of(2)), params, options.parseTransformTimeoutMillis());
                return checkedForms(result, transform.transform);
            } catch (ParseTransformException error) {
                throw error;
            } catch (Module.ApplyTimeoutException error) {
                throw new ParseTransformException(transform.transform.line,
                    "parse transform " + transform.transform.module
                        + " timed out after " + error.timeoutMillis() + " ms",
                    error);
            } catch (Throwable error) {
                throw new ParseTransformException(transform.transform.line,
                    "parse transform " + transform.transform.module
                        + " failed: " + message(error),
                    error);
            } finally {
                if (previous == null) {
                    ModuleRegistry.unregister(transform.transform.module);
                } else {
                    ModuleRegistry.register(previous);
                }
                for (String path : addedPaths) {
                    CodePaths.delete(path);
                }
            }
        }
    }

    /**
     * Put the directory that contains the transform at the front of the code
     * path for the duration of the call. The transform itself is registered
     * directly; this is what lets it call a helper module compiled beside it.
     */
    private static List<String> addCodePath(File transform) {
        File parent = transform.getAbsoluteFile().getParentFile();
        if (parent == null) {
            return java.util.Collections.emptyList();
        }
        String path = CodePaths.normalizePath(parent.getPath());
        List<String> current = CodePaths.get();
        if (current.contains(path)) {
            return java.util.Collections.emptyList();
        }
        CodePaths.addPatha(path);
        return java.util.Collections.singletonList(path);
    }

    private static List<Term> checkedForms(Term result, Transform transform) {
        if (!(result instanceof org.jerlang.type.List)) {
            throw new ParseTransformException(transform.line,
                "parse transform " + transform.module
                    + " did not return a form list");
        }
        List<Term> forms = new ArrayList<>();
        org.jerlang.type.List list = (org.jerlang.type.List) result;
        while (list != org.jerlang.type.List.nil) {
            Term form = list.head();
            if (!(form instanceof Tuple)) {
                throw new ParseTransformException(transform.line,
                    "parse transform " + transform.module
                        + " returned a non-form " + form);
            }
            Tuple tuple = (Tuple) form;
            if (tuple.arity() > 0 && isAtom(tuple.element(1), "function")
                && tuple.arity() != 5) {
                throw new ParseTransformException(transform.line,
                    "parse transform " + transform.module
                        + " returned a malformed function form " + form);
            }
            if (tuple.arity() > 0 && isAtom(tuple.element(1), "attribute")
                && tuple.arity() < 4) {
                throw new ParseTransformException(transform.line,
                    "parse transform " + transform.module
                        + " returned a malformed attribute form " + form);
            }
            forms.add(form);
            list = list.tail();
        }
        return forms;
    }

    private static org.jerlang.type.List list(List<Term> forms) {
        org.jerlang.type.List list = org.jerlang.type.List.nil;
        for (int index = forms.size() - 1; index >= 0; index--) {
            list = new org.jerlang.type.List(forms.get(index), list);
        }
        return list;
    }

    private static Term compilerOptions(Compiler.Options options) {
        List<Term> terms = new ArrayList<>();
        if (options.debugInfo()) {
            terms.add(Atom.of("debug_info"));
        }
        if (options.warningsAsErrors()) {
            terms.add(Atom.of("warnings_as_errors"));
        }
        for (String include : options.includePath()) {
            terms.add(Tuple.of(Atom.of("i"), Str.of(include)));
        }
        for (java.util.Map.Entry<String, String> define : options.defines().entrySet()) {
            terms.add(Tuple.of(Atom.of("d"), Atom.of(define.getKey()),
                defineValue(define.getValue())));
        }
        return list(terms);
    }

    /**
     * A -D value is an Erlang expression, not a string. The common case is one
     * literal token; anything more complex is passed as its source spelling so
     * a transform can still inspect it without the compiler pretending to be a
     * full term parser.
     */
    private static Term defineValue(String value) {
        if (value == null) {
            return Atom.of("true");
        }
        List<Token> tokens = new ArrayList<>(Scanner.scan(value));
        tokens.removeIf(token -> token.category().equals(Atom.of("dot")));
        if (tokens.size() == 1) {
            Term token = tokens.get(0).value();
            if (token instanceof org.jerlang.type.List) {
                return Str.convert((org.jerlang.type.List) token);
            }
            if (token != null) {
                return token;
            }
        }
        return Str.of(value);
    }

    private static List<Transform> collect(List<Term> forms) {
        List<Transform> transforms = new ArrayList<>();
        for (Term form : forms) {
            if (!(form instanceof Tuple)) {
                continue;
            }
            Tuple tuple = (Tuple) form;
            if (tuple.arity() < 4
                || !isAtom(tuple.element(1), "attribute")
                || !isAtom(tuple.element(3), "compile")) {
                continue;
            }
            int line = lineOf(tuple.element(2));
            for (Term option : options(tuple.element(4))) {
                if (!(option instanceof Tuple)) {
                    continue;
                }
                Tuple candidate = (Tuple) option;
                if (candidate.arity() < 2
                    || !isAtom(candidate.element(1), "parse_transform")) {
                    continue;
                }
                if (candidate.arity() != 2
                    || !(candidate.element(2) instanceof Atom)) {
                    throw new ParseTransformException(line,
                        "malformed parse_transform option " + option);
                }
                transforms.add(new Transform((Atom) candidate.element(2), line));
            }
        }
        return transforms;
    }

    private static List<Term> options(Term value) {
        List<Term> options = new ArrayList<>();
        if (value instanceof org.jerlang.type.List) {
            org.jerlang.type.List list = (org.jerlang.type.List) value;
            while (list != org.jerlang.type.List.nil) {
                options.add(list.head());
                list = list.tail();
            }
        } else {
            options.add(value);
        }
        return options;
    }

    private static File find(Atom module, Compiler.Options options) {
        String name = module + ".beam";
        for (String root : options.libraryPath()) {
            Path base = Paths.get(root);
            File direct = base.resolve(name).toFile();
            if (direct.isFile()) {
                return direct;
            }
            File ebin = base.resolve("ebin").resolve(name).toFile();
            if (ebin.isFile()) {
                return ebin;
            }
            File fromLibrary = findBelow(base, name);
            if (fromLibrary != null) {
                return fromLibrary;
            }
        }
        String path = CodePaths.find(module.toString());
        return path == null ? null : new File(path);
    }

    /**
     * Search the app-version directories below a library root, and the versioned
     * applications below its lib directory.
     */
    private static File findBelow(Path base, String name) {
        File found = findInApplications(base, name);
        if (found != null) {
            return found;
        }
        return findInApplications(base.resolve("lib"), name);
    }

    private static File findInApplications(Path root, String name) {
        if (!Files.isDirectory(root)) {
            return null;
        }
        try (java.util.stream.Stream<Path> entries = Files.list(root)) {
            List<Path> directories = entries.filter(Files::isDirectory).sorted().toList();
            for (Path directory : directories) {
                File file = directory.resolve("ebin").resolve(name).toFile();
                if (file.isFile()) {
                    return file;
                }
            }
        } catch (IOException ignored) {
            // The next root, or the code path, may have the module.
        }
        return null;
    }

    private static boolean isAtom(Term term, String name) {
        return term instanceof Atom && name.equals(term.toString());
    }

    private static int lineOf(Term annotation) {
        if (annotation instanceof Integer) {
            return ((Integer) annotation).toInt();
        }
        if (annotation instanceof org.jerlang.type.List) {
            org.jerlang.type.List list = (org.jerlang.type.List) annotation;
            while (list != org.jerlang.type.List.nil) {
                Term item = list.head();
                if (item instanceof Tuple) {
                    Tuple tuple = (Tuple) item;
                    if (tuple.arity() == 2
                        && isAtom(tuple.element(1), "location")
                        && tuple.element(2) instanceof Integer) {
                        return ((Integer) tuple.element(2)).toInt();
                    }
                }
                list = list.tail();
            }
        }
        return 1;
    }

    private static String message(Throwable error) {
        String message = error.getMessage();
        return message == null || message.isEmpty() ? error.toString() : message;
    }

    private static final class Transform {

        private final Atom module;
        private final int line;

        private Transform(Atom module, int line) {
            this.module = module;
            this.line = line;
        }

    }

    private static final class LoadedTransform {

        private final Transform transform;
        private final File file;
        private Module module;

        private LoadedTransform(Transform transform, File file) {
            this.transform = transform;
            this.file = file;
        }

    }

}
