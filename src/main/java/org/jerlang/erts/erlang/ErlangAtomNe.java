package org.jerlang.erts.erlang;

import org.jerlang.type.Atom;
import org.jerlang.type.List;
import org.jerlang.type.Term;

/**
 * The `/=` operator, which compares numbers by value: 1 /= 1.0 is false.
 */
public class ErlangAtomNe {

    private ErlangAtomNe() {
    }

    public static Term dispatch(List params) {
        switch (params.length()) {
        case 2:
            Term a = params.head();
            params = params.tail();
            return atom_ne_2(a, params.head());
        default:
            throw Error.badarg;
        }
    }

    public static Atom atom_ne_2(Term a, Term b) {
        return ErlangCompare.ne(a, b);
    }

    public static Atom name() {
        return Atom.of("/=");
    }

}
