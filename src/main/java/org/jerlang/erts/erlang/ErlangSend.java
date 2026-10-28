package org.jerlang.erts.erlang;

import org.jerlang.Process;
import org.jerlang.erts.dist.Distribution;
import org.jerlang.type.List;
import org.jerlang.type.PID;
import org.jerlang.type.Term;
import org.jerlang.vm.VirtualMachine;

public class ErlangSend {

    private ErlangSend() {
    }

    public static Term dispatch(List params) {
        switch (params.length()) {
        case 2:
            return send_2(params.head(), params.tail().head());
        default:
            throw Error.badarg;
        }
    }

    /**
     * Send a message to a PID, a registered name, or a {Name, Node} tuple.
     * Returns the message, like the send operator does.
     *
     * http://www.erlang.org/doc/man/erlang.html#send-2
     */
    public static Term send_2(Term target, Term message) {
        Process process = VirtualMachine.self();
        PID from = process != null ? process.pid() : new PID(0);
        Distribution.send(target, from, message);
        return message;
    }

}
