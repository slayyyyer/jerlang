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
     * BEAM file was found and loaded, and `{error, nofile}` otherwise.
     */
    public static Term load_file(Atom module) {
        Module loaded = ModuleLoader.load(module);
        if (loaded == null) {
            return Tuple.of(Atom.of("error"), Atom.of("nofile"));
        }
        Module current = ModuleRegistry.loaded(module);
        if (current != null && current.isInternal()) {
            // The BEAM file replaces a built-in module but keeps the
            // primitives that the file declares without implementing.
            loaded.adoptBuiltins(current);
        }
        ModuleRegistry.register(loaded);
        return Tuple.of(Atom.of("module"), module);
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
