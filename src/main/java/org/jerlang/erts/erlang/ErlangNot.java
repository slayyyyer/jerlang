package org.jerlang.erts.erlang;

import org.jerlang.type.Atom;
import org.jerlang.type.Boolean;
import org.jerlang.type.List;
import org.jerlang.type.Term;

/**
 * The guard/expression `not` operator.
 */
public class ErlangNot {

    private ErlangNot() {
    }

    public static Term dispatch(List params) {
        switch (params.length()) {
        case 1:
            return not_1(params.head());
        default:
            throw Error.badarg;
        }
    }

    public static Term not_1(Term term) {
        if (term.equals(Atom.of("true"))) {
            return Boolean.of(false);
        }
        if (term.equals(Atom.of("false"))) {
            return Boolean.of(true);
        }
        throw Error.badarg;
    }

}
