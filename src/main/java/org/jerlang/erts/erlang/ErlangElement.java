package org.jerlang.erts.erlang;

import org.jerlang.type.Integer;
import org.jerlang.type.List;
import org.jerlang.type.Term;
import org.jerlang.type.Tuple;

/**
 * erlang:element/2 returns the element at a one-based position of a tuple.
 * It is a BIF, so OTP's erlang.beam only carries a nif_error stub for it, and
 * OTP code that calls element/2 directly (gen_server does, on its state
 * record) needs the runtime implementation.
 *
 * http://www.erlang.org/doc/man/erlang.html#element-2
 */
public class ErlangElement {

    private ErlangElement() {
    }

    public static Term dispatch(List params) {
        switch (params.length()) {
        case 2:
            return element_2(params.head().toInteger(), params.tail().head());
        default:
            throw Error.badarg;
        }
    }

    public static Term element_2(Integer index, Term tuple) {
        if (!(tuple instanceof Tuple)) {
            throw Error.badarg;
        }
        int position = index.toInt();
        Tuple elements = (Tuple) tuple;
        if (position < 1 || position > elements.arity()) {
            throw Error.badarg;
        }
        return elements.element(position);
    }

}
