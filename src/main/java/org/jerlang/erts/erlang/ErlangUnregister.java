package org.jerlang.erts.erlang;

import org.jerlang.ProcessRegistry;
import org.jerlang.type.Atom;
import org.jerlang.type.List;
import org.jerlang.type.Term;

public class ErlangUnregister {

    private ErlangUnregister() {
    }

    public static Term dispatch(List params) {
        if (params.length() != 1) {
            throw Error.badarg;
        }
        return unregister_1(params.head());
    }

    /**
     * Removes the registered name. Raises badarg when the name is not
     * registered, as Erlang/OTP does.
     *
     * http://www.erlang.org/doc/man/erlang.html#unregister-1
     */
    public static Term unregister_1(Term name) {
        if (!(name instanceof Atom) || !ProcessRegistry.unregister((Atom) name)) {
            throw Error.badarg;
        }
        return org.jerlang.type.Boolean.am_true;
    }

}
