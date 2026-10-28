package org.jerlang.erts.erlang;

import org.jerlang.ProcessRegistry;
import org.jerlang.type.Boolean;
import org.jerlang.type.List;
import org.jerlang.type.PID;
import org.jerlang.type.Term;

public class ErlangIsProcessAlive {

    private ErlangIsProcessAlive() {
    }

    public static Term dispatch(List params) {
        if (params.length() != 1) {
            throw Error.badarg;
        }
        return is_process_alive_1(params.head());
    }

    /**
     * True when the process is still running on this node.
     *
     * http://www.erlang.org/doc/man/erlang.html#is_process_alive-1
     */
    public static Term is_process_alive_1(Term pid) {
        if (!(pid instanceof PID)) {
            throw Error.badarg;
        }
        return Boolean.of(ProcessRegistry.isAlive((PID) pid));
    }

}
