package org.jerlang.erts.erlang;

import org.jerlang.type.Atom;
import org.jerlang.type.List;
import org.jerlang.type.Term;

/**
 * erlang:raise/3 raises an exception of the given class with the given
 * reason and stacktrace, which is how OTP re-raises an exception without
 * losing where it came from. gen_server:terminate uses it, so stopping a
 * server exits it with the callback's reason instead of undef.
 *
 * http://www.erlang.org/doc/man/erlang.html#raise-3
 */
public class ErlangRaise {

    private static final Atom ERROR = Atom.of("error");
    private static final Atom THROW = Atom.of("throw");
    private static final Atom EXIT = Atom.of("exit");

    private ErlangRaise() {
    }

    public static Term dispatch(List params) {
        switch (params.length()) {
        case 3:
            return raise_3(params.head().toAtom(), params.tail().head(),
                params.tail().tail().head());
        default:
            throw Error.badarg;
        }
    }

    public static Term raise_3(Atom exceptionClass, Term reason, Term stacktrace) {
        if (!ERROR.equals(exceptionClass) && !THROW.equals(exceptionClass)
            && !EXIT.equals(exceptionClass)) {
            throw Error.badarg;
        }
        if (!stacktrace.isList()) {
            throw Error.badarg;
        }
        throw new Error(exceptionClass, reason, stacktrace);
    }

}
