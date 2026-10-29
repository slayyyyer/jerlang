package org.jerlang.erts.erlang;

import org.jerlang.type.Atom;
import org.jerlang.type.List;
import org.jerlang.type.Term;

public class ErlangThrow {

    private ErlangThrow() {
    }

    public static Term dispatch(List params) throws Error {
        switch (params.length()) {
        case 1:
            return throw_1(params.head());
        default:
            throw Error.badarg;
        }
    }

    /**
     * A non-local return from a function.
     * If evaluated within a catch, catch will return the value Any.
     *
     * http://www.erlang.org/doc/man/erlang.html#throw-1
     */
    public static Term throw_1(Term term) throws Error {
        // A throw is its own exception class, so catch throw:Value matches
        // it and an uncaught throw is reported as {nocatch, Value}.
        throw new Error(Atom.of("throw"), term);
    }

}
