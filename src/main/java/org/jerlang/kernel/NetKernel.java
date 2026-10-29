package org.jerlang.kernel;

import org.jerlang.ProcessSignals;
import org.jerlang.erts.dist.DistNodeName;
import org.jerlang.erts.dist.Distribution;
import org.jerlang.erts.dist.NodeMonitorOptions;
import org.jerlang.erts.erlang.Error;
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
    private static final Atom INVALID_OPTIONS = Atom.of("invalid_options");
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
     * Subscribe the caller to nodeup/nodedown messages. The options are
     * parsed by NodeMonitorOptions; an invalid option list is reported as
     * {error, {invalid_options, Options}}, as net_kernel does.
     */
    public static Term monitor_nodes(Term flag, Term options) {
        if (!(flag instanceof Atom)
            || !(Boolean.am_true.equals(flag) || Boolean.am_false.equals(flag))) {
            return ERROR;
        }
        NodeMonitorOptions parsed;
        try {
            parsed = NodeMonitorOptions.parse(options);
        } catch (IllegalArgumentException e) {
            return Tuple.of(ERROR, Tuple.of(INVALID_OPTIONS, options));
        }
        if (!DistNodeName.isValid(VirtualMachine.instance().node())) {
            return Tuple.of(ERROR, NOTALIVE);
        }
        ProcessSignals.self().monitorNodeEvents(parsed, Boolean.am_true.equals(flag));
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
