package org.jerlang.stdlib.code;

import org.jerlang.ModuleRegistry;
import org.jerlang.erts.erlang.Error;
import org.jerlang.stdlib.Code;
import org.jerlang.type.Atom;
import org.jerlang.type.List;
import org.jerlang.type.Term;
import org.jerlang.type.Tuple;

public class CodeEnsureLoaded {

    private static final Atom MODULE = Atom.of("module");

    private CodeEnsureLoaded() {
    }

    public static Term dispatch(List params) {
        switch (params.length()) {
        case 1:
            return ensure_loaded(params.head().toAtom());
        default:
            throw Error.badarg;
        }
    }

    /**
     * http://www.erlang.org/doc/man/code.html#ensure_loaded-1
     */
    public static Term ensure_loaded(Atom module) {
        if (ModuleRegistry.loaded(module) != null) {
            return Tuple.of(MODULE, module);
        }
        // load_file reports both the loaded module and the reason it could
        // not be loaded in the shape ensure_loaded returns.
        return Code.load_file(module);
    }

}
