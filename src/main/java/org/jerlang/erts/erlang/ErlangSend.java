package org.jerlang.erts.erlang;

import org.jerlang.Process;
import org.jerlang.erts.dist.Distribution;
import org.jerlang.type.Atom;
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
        case 3:
            return send_3(params.head(), params.tail().head(), params.tail().tail().head());
        default:
            throw Error.badarg;
        }
    }

    /**
     * send/3 takes the options `noconnect`, `nosuspend` and `abort`. They
     * describe what may happen while a connection is set up, which this
     * runtime does synchronously, so the message is sent either way.
     *
     * http://www.erlang.org/doc/man/erlang.html#send-3
     */
    public static Term send_3(Term target, Term message, Term options) {
        List list = options.toList();
        while (list.length() > 0) {
            Term option = list.head();
            if (!(option instanceof Atom)) {
                throw Error.badarg;
            }
            switch (option.toString()) {
            case "noconnect":
            case "nosuspend":
            case "abort":
                break;
            default:
                throw Error.badarg;
            }
            list = list.tail();
        }
        return send_2(target, message);
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
