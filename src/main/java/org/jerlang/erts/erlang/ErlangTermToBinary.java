package org.jerlang.erts.erlang;

import org.jerlang.erts.etf.ExternalTermWriter;
import org.jerlang.type.Binary;
import org.jerlang.type.List;
import org.jerlang.type.Term;

public class ErlangTermToBinary {

    private ErlangTermToBinary() {
    }

    public static Term dispatch(List params) {
        switch (params.length()) {
        case 1:
            return term_to_binary_1(params.head());
        default:
            throw Error.badarg;
        }
    }

    /**
     * Encode a term in the Erlang external term format.
     *
     * http://www.erlang.org/doc/man/erlang.html#term_to_binary-1
     */
    public static Binary term_to_binary_1(Term term) {
        return ExternalTermWriter.encode(term);
    }

}
