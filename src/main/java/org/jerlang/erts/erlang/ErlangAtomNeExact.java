package org.jerlang.erts.erlang;

import org.jerlang.type.Atom;
import org.jerlang.type.List;
import org.jerlang.type.Term;

/**
 * The `=/=` operator, which does not consider 1 and 1.0 equal.
 */
public class ErlangAtomNeExact {

    private ErlangAtomNeExact() {
    }

    public static Term dispatch(List params) {
        switch (params.length()) {
        case 2:
            Term a = params.head();
            params = params.tail();
            return atom_ne_exact_2(a, params.head());
        default:
            throw Error.badarg;
        }
    }

    public static Atom atom_ne_exact_2(Term a, Term b) {
        return ErlangCompare.neExact(a, b);
    }

    public static Atom name() {
        return Atom.of("=/=");
    }

}
