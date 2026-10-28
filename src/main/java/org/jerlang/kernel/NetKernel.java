package org.jerlang.kernel;

import org.jerlang.ProcessSignals;
import org.jerlang.erts.dist.DistNodeName;
import org.jerlang.erts.erlang.Error;
import org.jerlang.erts.dist.Distribution;
import org.jerlang.type.Atom;
import org.jerlang.type.Boolean;
import org.jerlang.type.List;
import org.jerlang.type.Term;
import org.jerlang.type.Tuple;
import org.jerlang.vm.VirtualMachine;

/**
 * The small, local part of net_kernel that applications need: node
 * monitoring and explicit connect/disconnect.
 *
 * http://www.erlang.org/doc/man/net_kernel.html
 */
public class NetKernel {

    private static final Atom OK = Atom.of("ok");
    private static final Atom ERROR = Atom.of("error");
    private static final Atom NOTALIVE = Atom.of("notalive");
    private static final Atom IGNORED = Atom.of("ignored");
    private static final Atom TRUE = Atom.of("true");

    private NetKernel() {
    }

    /**
     * Subscribe the caller to nodeup/nodedown messages.
     */
    public static Term monitor_nodes(Term flag) {
        return monitor_nodes(flag, List.nil);
    }

    /**
     * The supported option container is an empty list or an empty map.
     * JErlang has no hidden nodes and no nodedown reason details yet.
     */
    public static Term monitor_nodes(Term flag, Term options) {
        if (!(flag instanceof Atom) || !(Boolean.am_true.equals(flag) || Boolean.am_false.equals(flag))) {
            throw Error.badarg;
        }
        if (options instanceof List && options.toList().length() == 0) {
            // No options.
        } else if (options instanceof org.jerlang.type.Map
            && ((org.jerlang.type.Map) options).keys().length() == 0) {
            // Empty options map.
        } else {
            throw Error.badarg;
        }
        if (!DistNodeName.isValid(VirtualMachine.instance().node())) {
            return Tuple.of(ERROR, NOTALIVE);
        }
        ProcessSignals.self().monitorNodeEvents(Boolean.am_true.equals(flag));
        return OK;
    }

    /**
     * Connect to another node, returning true when it is reachable.
     */
    public static Term connect_node(Term node) {
        if (!(node instanceof Atom)) {
            throw Error.badarg;
        }
        if (!DistNodeName.isValid(VirtualMachine.instance().node())) {
            return IGNORED;
        }
        return Boolean.of(Distribution.connect((Atom) node));
    }

    /**
     * Drop the connection to another node.
     */
    public static Term disconnect_node(Term node) {
        if (!(node instanceof Atom)) {
            throw Error.badarg;
        }
        Distribution.disconnect((Atom) node);
        return TRUE;
    }

    public static Term is_alive() {
        return Boolean.of(DistNodeName.isValid(VirtualMachine.instance().node()));
    }

}
