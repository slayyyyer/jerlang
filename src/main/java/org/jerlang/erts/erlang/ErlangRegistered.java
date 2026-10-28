package org.jerlang.erts.erlang;

import org.jerlang.ProcessRegistry;
import org.jerlang.type.List;
import org.jerlang.type.Term;

public class ErlangRegistered {

    private ErlangRegistered() {
    }

    public static Term dispatch(List params) {
        if (params.length() != 0) {
            throw Error.badarg;
        }
        return registered_0();
    }

    /**
     * Returns the names this node has registered, in no particular order.
     *
     * http://www.erlang.org/doc/man/erlang.html#registered-0
     */
    public static List registered_0() {
        return ProcessRegistry.registered();
    }

}
