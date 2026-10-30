package org.jerlang.erts.erlang;

import org.jerlang.type.List;
import org.jerlang.type.Term;

public class ErlangMax {

    private ErlangMax() {
    }

    public static Term dispatch(List params) {
        switch (params.length()) {
        case 2:
            return max_2(params.head(), params.tail().head());
        default:
            throw Error.badarg;
        }
    }

    /**
     * http://www.erlang.org/doc/man/erlang.html#max-2
     */
    public static Term max_2(Term a, Term b) {
        return ErlangMinMax.max(a, b);
    }

}
