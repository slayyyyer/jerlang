package org.jerlang.stdlib.lists;

import org.jerlang.erts.erlang.Error;
import org.jerlang.type.Boolean;
import org.jerlang.type.Integer;
import org.jerlang.type.List;
import org.jerlang.type.Term;
import org.jerlang.type.Tuple;

public class ListsKeyfind {

    private ListsKeyfind() {
    }

    public static Term dispatch(List params) {
        switch (params.length()) {
        case 3:
            Term key = params.head();
            params = params.tail();
            Integer position = params.head().toInteger();
            List tuples = params.tail().head().toList();
            return keyfind_3(key, position, tuples);
        default:
            throw Error.badarg;
        }
    }

    /**
     * Returns the first tuple in `tuples` whose element `position` compares
     * equal to `key`, or false. OTP implements this as a BIF, so lists.beam
     * only carries a nif_error placeholder for it.
     *
     * http://www.erlang.org/doc/man/lists.html#keyfind-3
     */
    public static Term keyfind_3(Term key, Integer position, List tuples) {
        Tuple tuple = search(key, position, tuples);
        return tuple == null ? Boolean.am_false : tuple;
    }

    /**
     * The first tuple whose element at the 1-based `position` equals `key`,
     * or null. A non-tuple element, or a tuple that is too short, is badarg,
     * as in Erlang/OTP.
     */
    static Tuple search(Term key, Integer position, List tuples) {
        int pos = position.toInt();
        List current = tuples;
        while (current.length() > 0) {
            Term head = current.head();
            if (!(head instanceof Tuple)) {
                throw Error.badarg;
            }
            Tuple tuple = (Tuple) head;
            if (tuple.arity() < pos || pos < 1) {
                throw Error.badarg;
            }
            if (key.equals(tuple.element(pos))) {
                return tuple;
            }
            current = current.tail();
        }
        return null;
    }

}
