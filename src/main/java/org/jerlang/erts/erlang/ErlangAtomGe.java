package org.jerlang.erts.erlang;

import org.jerlang.type.Atom;
import org.jerlang.type.List;
import org.jerlang.type.Term;

/**
 * The `&gt;=` operator.
 */
public class ErlangAtomGe {

    private ErlangAtomGe() {
    }

    public static Term dispatch(List params) {
        switch (params.length()) {
        case 2:
            Term a = params.head();
            params = params.tail();
            return atom_ge_2(a, params.head());
        default:
            throw Error.badarg;
        }
    }

    public static Atom atom_ge_2(Term a, Term b) {
        return ErlangCompare.ge(a, b);
    }

    public static Atom name() {
        return Atom.of(">=");
    }

}
