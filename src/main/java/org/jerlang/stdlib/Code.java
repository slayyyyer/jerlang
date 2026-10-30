package org.jerlang.stdlib;

import org.jerlang.CodePaths;
import org.jerlang.Module;
import org.jerlang.ModuleLoader;
import org.jerlang.ModuleRegistry;
import org.jerlang.erts.erlang.Error;
import org.jerlang.type.Atom;
import org.jerlang.type.List;
import org.jerlang.type.Str;
import org.jerlang.type.Term;
import org.jerlang.type.Tuple;

/**
 * = code
 *
 * The code server: the search path it looks for BEAM files on, and the
 * functions that inspect and load modules from it.
 *
 * Only the implemented functions are declared here. The registry exports one
 * function per declaration, so a declaration without an implementation class
 * would be an exported function that can not be called.
 *
 * http://www.erlang.org/doc/man/code.html
 */
public class Code {

    /**
     * Add a directory to the front of the code path.
     */
    public static Term add_patha(Term directory) {
        String path = PathArgument.of(directory);
        if (!new java.io.File(path).isDirectory()) {
            return Tuple.of(Atom.of("error"), Atom.of("bad_directory"));
        }
        CodePaths.addPatha(path);
        return Atom.of("true");
    }

    /**
     * Add a directory to the end of the code path.
     */
    public static Term add_pathz(Term directory) {
        String path = PathArgument.of(directory);
        if (!new java.io.File(path).isDirectory()) {
            return Tuple.of(Atom.of("error"), Atom.of("bad_directory"));
        }
        CodePaths.addPathz(path);
        return Atom.of("true");
    }

    /**
     * Remove a directory from the code path.
     */
    public static Term del_path(Term name) {
        return CodePaths.delete(PathArgument.of(name)) ? Atom.of("true") : Atom.of("false");
    }

    /**
     * The directories of the code path, in search order.
     */
    public static List get_path() {
        List path = List.nil;
        java.util.List<String> directories = CodePaths.get();
        for (int index = directories.size() - 1; index >= 0; index--) {
            path = new List(Str.of(directories.get(index)), path);
        }
        return path;
    }

    /**
     * What the code server knows about a module: the BEAM file it would load,
     * `preloaded` when the runtime implements it, or `non_existing`.
     */
    public static Term is_loaded(Atom module) {
        Module loaded = ModuleRegistry.loaded(module);
        if (loaded == null) {
            return Atom.of("false");
        }
        if (loaded.beamFile() == null) {
            return Tuple.of(Atom.of("file"), Str.of("preloaded"));
        }
        return Tuple.of(Atom.of("file"),
            Str.of(CodePaths.normalizePath(loaded.beamFile().getPath())));
    }

    /**
     * The library directory of an application: the directory above its
     * `ebin` directory.
     */
    public static Term lib_dir(Atom application) {
        String libDir = CodePaths.libDir(application.toString());
        if (libDir == null) {
            return Tuple.of(Atom.of("error"), Atom.of("bad_name"));
        }
        return Str.of(libDir);
    }

    /**
     * A directory below the library directory of an application.
     */
    public static Term lib_dir(Atom application, Term subDirectory) {
        Term libDir = lib_dir(application);
        if (libDir instanceof Tuple) {
            return libDir;
        }
        return Str.of(libDir.toStr().string() + "/" + PathArgument.of(subDirectory));
    }

    /**
     * Make sure a module is loaded: `{module, Module}` when it already is or
     * was just loaded, `{error, Reason}` when the code server can not load
     * it.
     */
    public static Term ensure_loaded(Term module) {
        return org.jerlang.stdlib.code.CodeEnsureLoaded.ensure_loaded(module.toAtom());
    }

    /**
     * Load a module from the code path. Returns `{module, Module}` when the
     * BEAM file was found and loaded, and `{error, nofile}` otherwise. A
     * module already loaded from a sticky directory may only be replaced by
     * that same file, which is what code:stick_dir/1 protects.
     */
    public static Term load_file(Atom module) {
        Module current = ModuleRegistry.loaded(module);
        String target = CodePaths.find(module.toString());
        if (current != null && current.beamFile() != null && target != null
            && !CodePaths.normalizePath(current.beamFile().getPath()).equals(target)
            && CodePaths.isSticky(CodePaths.parentOf(current.beamFile().getPath()))) {
            return Tuple.of(Atom.of("error"), Atom.of("sticky_directory"));
        }
        Module loaded = ModuleLoader.load(module);
        if (loaded == null) {
            return Tuple.of(Atom.of("error"), Atom.of("nofile"));
        }
        Module existing = ModuleRegistry.loaded(module);
        if (existing != null && existing.isInternal()) {
            // The BEAM file replaces a built-in module but keeps the
            // primitives that the file declares without implementing.
            loaded.adoptBuiltins(existing);
        }
        ModuleRegistry.register(loaded);
        return Tuple.of(Atom.of("module"), module);
    }

    /**
     * Load several modules. OTP loads them in parallel; module loading and
     * the atom table are not thread safe in this runtime, so the loads here
     * are sequential. `ok` means every module is loaded, otherwise the result
     * lists the modules that failed with their reason.
     *
     * http://www.erlang.org/doc/man/code.html#ensure_modules_loaded-1
     */
    public static Term ensure_modules_loaded(Term modules) {
        java.util.List<Term> failures = new java.util.ArrayList<>();
        List entries = modules.toList();
        while (entries.length() > 0) {
            Term entry = entries.head();
            if (!(entry instanceof Atom)) {
                throw Error.badarg;
            }
            Atom module = (Atom) entry;
            if (ModuleRegistry.loaded(module) == null) {
                Term result = load_file(module);
                if (!(result instanceof Tuple) || ((Tuple) result).arity() != 2
                    || !Atom.of("module").equals(((Tuple) result).element(1))) {
                    Term reason = result instanceof Tuple && ((Tuple) result).arity() == 2
                        ? ((Tuple) result).element(2) : result;
                    failures.add(Tuple.of(module, reason));
                }
            }
            entries = entries.tail();
        }
        if (failures.isEmpty()) {
            return Atom.of("ok");
        }
        List list = List.nil;
        for (int index = failures.size() - 1; index >= 0; index--) {
            list = new List(failures.get(index), list);
        }
        return Tuple.of(Atom.of("error"), list);
    }

    /**
     * Make a directory sticky, so a module already loaded from it can not be
     * replaced from another directory.
     */
    public static Term stick_dir(Term directory) {
        return CodePaths.stick(PathArgument.of(directory))
            ? Atom.of("ok") : Tuple.of(Atom.of("error"), Atom.of("bad_directory"));
    }

    /**
     * Remove the sticky mark from a directory.
     */
    public static Term unstick_dir(Term directory) {
        return CodePaths.unstick(PathArgument.of(directory))
            ? Atom.of("ok") : Tuple.of(Atom.of("error"), Atom.of("bad_directory"));
    }

    public static Term is_sticky(Term directory) {
        return CodePaths.isSticky(PathArgument.of(directory))
            ? Atom.of("true") : Atom.of("false");
    }

    /**
     * Replace one entry of the code path with another directory. The old
     * entry has to be on the path and the new one has to exist.
     */
    public static Term replace_path(Term name, Term directory) {
        String from = PathArgument.of(name);
        String to = PathArgument.of(directory);
        if (!new java.io.File(to).isDirectory()) {
            return Tuple.of(Atom.of("error"), Atom.of("bad_directory"));
        }
        if (!CodePaths.replace(from, to)) {
            return Tuple.of(Atom.of("error"), Atom.of("bad_path"));
        }
        return Atom.of("true");
    }


    /**
     * The private directory of an application.
     */
    public static Term priv_dir(Atom application) {
        String privDir = CodePaths.privDir(application.toString());
        if (privDir == null) {
            return Tuple.of(Atom.of("error"), Atom.of("bad_name"));
        }
        return Str.of(privDir);
    }

    /**
     * The installation directory of the runtime.
     */
    public static Str root_dir() {
        return Str.of(CodePaths.rootDir());
    }

    /**
     * The file the code server would load a module from, as an atom.
     */
    public static Term which(Atom module) {
        String path = CodePaths.find(module.toString());
        if (path != null) {
            return Atom.of(path);
        }
        if (ModuleRegistry.loaded(module) != null) {
            return Atom.of("preloaded");
        }
        return Atom.of("non_existing");
    }

    /**
     * Path arguments. Kept out of this class because every declared method
     * here is exported as an Erlang function.
     */
    private static final class PathArgument {

        private PathArgument() {
        }

        private static String of(Term term) {
            if (term instanceof Atom) {
                return term.toString();
            }
            if (term instanceof Str) {
                return term.toStr().string();
            }
            throw Error.badarg;
        }

    }

}
