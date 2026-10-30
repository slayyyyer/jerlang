package org.jerlang.erts.erlang;

import org.jerlang.type.List;
import org.jerlang.type.Term;

public class ErlangMin {

    private ErlangMin() {
    }

    public static Term dispatch(List params) {
        switch (params.length()) {
        case 2:
            return min_2(params.head(), params.tail().head());
        default:
            throw Error.badarg;
        }
    }

    /**
     * http://www.erlang.org/doc/man/erlang.html#min-2
     */
    public static Term min_2(Term a, Term b) {
        return ErlangMinMax.min(a, b);
    }

}
