package org.jerlang.erts.erlang;

import org.jerlang.stdlib.Lists;
import org.jerlang.type.Atom;
import org.jerlang.type.List;
import org.jerlang.type.Term;

/**
 * The list subtraction operator.
 */
public class ErlangAtomMinusMinus {

    private ErlangAtomMinusMinus() {
    }

    public static Term dispatch(List params) {
        switch (params.length()) {
        case 2:
            List a = params.head().toList();
            params = params.tail();
            List b = params.head().toList();
            return atom_minus_minus_2(a, b);
        default:
            throw Error.badarg;
        }
    }

    public static List atom_minus_minus_2(List a, List b) {
        List result = a;
        while (b != List.nil) {
            result = Lists.delete(b.head(), result);
            b = b.tail();
        }
        return result;
    }

    public static Atom name() {
        return Atom.of("--");
    }

}
