package org.jerlang.erts.erlang;

import org.jerlang.erts.etf.ExternalTermReader;
import org.jerlang.type.Binary;
import org.jerlang.type.List;
import org.jerlang.type.Term;

public class ErlangBinaryToTerm {

    private ErlangBinaryToTerm() {
    }

    public static Term dispatch(List params) {
        switch (params.length()) {
        case 1:
            return binary_to_term_1(params.head());
        default:
            throw Error.badarg;
        }
    }

    /**
     * Decode a term in the Erlang external term format.
     *
     * http://www.erlang.org/doc/man/erlang.html#binary_to_term-1
     */
    public static Term binary_to_term_1(Term term) {
        if (!(term instanceof Binary)) {
            throw Error.badarg;
        }
        try {
            return new ExternalTermReader(((Binary) term).toByteArray()).read();
        } catch (Throwable e) {
            // An invalid external term is a badarg in Erlang/OTP.
            throw Error.badarg;
        }
    }

}
