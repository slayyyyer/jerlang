package org.jerlang.erts.erlang;

import org.jerlang.ProcessSignals;
import org.jerlang.type.List;
import org.jerlang.type.PID;
import org.jerlang.type.Term;

public class ErlangLink {

    private ErlangLink() {
    }

    public static Term dispatch(List params) {
        if (params.length() != 1) {
            throw Error.badarg;
        }
        return link_1(params.head());
    }

    /**
     * Creates a link between the calling process and another process.
     *
     * http://www.erlang.org/doc/man/erlang.html#link-1
     */
    public static Term link_1(Term target) {
        if (!(target instanceof PID)) {
            throw Error.badarg;
        }
        ProcessSignals.self().link((PID) target);
        return org.jerlang.type.Boolean.am_true;
    }

}
