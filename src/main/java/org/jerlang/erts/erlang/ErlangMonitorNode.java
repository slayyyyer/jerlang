package org.jerlang.erts.erlang;

import org.jerlang.ProcessSignals;
import org.jerlang.erts.dist.DistNodeName;
import org.jerlang.type.Atom;
import org.jerlang.type.Boolean;
import org.jerlang.type.List;
import org.jerlang.type.Term;
import org.jerlang.vm.VirtualMachine;

/**
 * Monitor the status of a node.
 *
 * http://www.erlang.org/doc/man/erlang.html#monitor_node-2
 */
public class ErlangMonitorNode {

    private static final Atom ALLOW_PASSIVE_CONNECT = Atom.of("allow_passive_connect");
    private static final Atom NOTALIVE = Atom.of("notalive");

    private ErlangMonitorNode() {
    }

    public static Term dispatch(List params) {
        switch (params.length()) {
        case 2:
            return monitor_node_2(params.head(), params.tail().head());
        case 3:
            return monitor_node_3(params.head(), params.tail().head(), params.tail().tail().head());
        default:
            throw Error.badarg;
        }
    }

    /**
     * Turn monitoring of `node` on or off. One call makes one independent
     * subscription; the subscription ends when the node goes down.
     */
    public static Term monitor_node_2(Term node, Term flag) {
        return monitor(node, flag, List.nil);
    }

    /**
     * As monitor_node/2, with the single documented option
     * `allow_passive_connect`. JErlang always allows a passive connection,
     * so the option does not change anything here.
     */
    public static Term monitor_node_3(Term node, Term flag, Term options) {
        return monitor(node, flag, options);
    }

    private static Term monitor(Term node, Term flag, Term options) {
        if (!(node instanceof Atom)
            || !(Boolean.am_true.equals(flag) || Boolean.am_false.equals(flag))
            || !(options instanceof List)) {
            throw Error.badarg;
        }
        List rest = options.toList();
        while (rest.length() > 0) {
            if (!ALLOW_PASSIVE_CONNECT.equals(rest.head())) {
                throw Error.badarg;
            }
            rest = rest.tail();
        }
        if (!DistNodeName.isValid(VirtualMachine.instance().node())) {
            throw new Error(NOTALIVE);
        }
        ProcessSignals.self().monitorNode((Atom) node, Boolean.am_true.equals(flag));
        return Boolean.am_true;
    }

}
