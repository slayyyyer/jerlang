package org.jerlang.erts.erlang;

import org.jerlang.Process;
import org.jerlang.ProcessOrPort;
import org.jerlang.ProcessRegistry;
import org.jerlang.ProcessSignals;
import org.jerlang.erts.dist.Distribution;
import org.jerlang.type.Atom;
import org.jerlang.type.List;
import org.jerlang.type.PID;
import org.jerlang.type.Term;

public class ErlangExit {

    private ErlangExit() {
    }

    public static Term dispatch(List params) {
        switch (params.length()) {
        case 1:
            return exit_1(params.head());
        case 2:
            return exit_2(params.head(), params.tail().head());
        default:
            throw Error.badarg;
        }
    }

    /**
     * Stops the calling process with the given reason. The exception class
     * is `exit`, so `try ... catch exit:Reason` can catch it.
     *
     * http://www.erlang.org/doc/man/erlang.html#exit-1
     */
    public static Term exit_1(Term reason) {
        throw new Error(Atom.of("exit"), reason);
    }

    /**
     * Sends an exit signal with the given reason to another process. `kill`
     * is uncatchable and terminates the peer with reason `killed`.
     *
     * http://www.erlang.org/doc/man/erlang.html#exit-2
     */
    public static Term exit_2(Term target, Term reason) {
        if (!(target instanceof PID)) {
            throw Error.badarg;
        }
        PID pid = (PID) target;
        PID self = ProcessSignals.self().pid();
        if (!Process.onThisNode(pid)) {
            // EXIT2 is the explicit signal of exit/2, unlike the EXIT that
            // a broken link produces.
            Distribution.sendExit(self, pid, reason, true);
            return org.jerlang.type.Boolean.am_true;
        }
        ProcessOrPort peer = ProcessRegistry.resolve(pid);
        if (peer instanceof Process) {
            ((Process) peer).exitSignal(reason, self);
        }
        return org.jerlang.type.Boolean.am_true;
    }

}
