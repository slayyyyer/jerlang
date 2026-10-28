package org.jerlang.erts.erlang;

import org.jerlang.ProcessSignals;
import org.jerlang.type.List;
import org.jerlang.type.PID;
import org.jerlang.type.Term;

public class ErlangUnlink {

    private ErlangUnlink() {
    }

    public static Term dispatch(List params) {
        if (params.length() != 1) {
            throw Error.badarg;
        }
        return unlink_1(params.head());
    }

    /**
     * Removes a link between the calling process and another process, if
     * there is one.
     *
     * http://www.erlang.org/doc/man/erlang.html#unlink-1
     */
    public static Term unlink_1(Term target) {
        if (!(target instanceof PID)) {
            throw Error.badarg;
        }
        ProcessSignals.self().unlink((PID) target);
        return org.jerlang.type.Boolean.am_true;
    }

}
