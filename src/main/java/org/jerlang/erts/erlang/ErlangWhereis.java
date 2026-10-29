package org.jerlang.erts.erlang;

import org.jerlang.ProcessRegistry;
import org.jerlang.type.Atom;
import org.jerlang.type.List;
import org.jerlang.type.PidOrPortId;
import org.jerlang.type.Term;

public class ErlangWhereis {

    private ErlangWhereis() {
    }

    public static Term dispatch(List params) {
        if (params.length() != 1) {
            throw Error.badarg;
        }
        return whereis_1(params.head());
    }

    /**
     * Returns the process or port registered under a name, or `undefined`.
     *
     * http://www.erlang.org/doc/man/erlang.html#whereis-1
     */
    public static Term whereis_1(Term name) {
        if (!(name instanceof Atom)) {
            throw Error.badarg;
        }
        PidOrPortId id = ProcessRegistry.whereis((Atom) name);
        return id == null ? Atom.of("undefined") : id;
    }

}
