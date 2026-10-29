package org.jerlang.erts.erlang;

import org.jerlang.type.Atom;
import org.jerlang.type.List;
import org.jerlang.type.Term;

/**
 * The `=&lt;` operator.
 */
public class ErlangAtomLe {

    private ErlangAtomLe() {
    }

    public static Term dispatch(List params) {
        switch (params.length()) {
        case 2:
            Term a = params.head();
            params = params.tail();
            return atom_le_2(a, params.head());
        default:
            throw Error.badarg;
        }
    }

    public static Atom atom_le_2(Term a, Term b) {
        return ErlangCompare.le(a, b);
    }

    public static Atom name() {
        return Atom.of("=<");
    }

}
