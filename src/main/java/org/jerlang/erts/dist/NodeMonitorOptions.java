package org.jerlang.erts.dist;

import org.jerlang.type.Atom;
import org.jerlang.type.Boolean;
import org.jerlang.type.Integer;
import org.jerlang.type.List;
import org.jerlang.type.Map;
import org.jerlang.type.Term;
import org.jerlang.type.Tuple;

/**
 * The options of one net_kernel:monitor_nodes/2 subscription.
 *
 * OTP stores these options as an integer key on a process monitor, which
 * makes two subscriptions with the same set of options identical. This
 * class is that key, and also builds the nodeup/nodedown message that the
 * subscription asked for.
 *
 * http://www.erlang.org/doc/man/net_kernel.html#monitor_nodes-2
 */
public final class NodeMonitorOptions {

    public static final int VISIBLE = 1;
    public static final int HIDDEN = 2;
    public static final int ALL = VISIBLE | HIDDEN;

    private static final Atom CONNECTION_ID = Atom.of("connection_id");
    private static final Atom NODE_TYPE = Atom.of("node_type");
    private static final Atom NODEDOWN_REASON = Atom.of("nodedown_reason");
    private static final Atom VISIBLE_ATOM = Atom.of("visible");
    private static final Atom HIDDEN_ATOM = Atom.of("hidden");
    private static final Atom ALL_ATOM = Atom.of("all");

    private final boolean infoMap;
    private final boolean connectionId;
    private final boolean nodedownReason;
    private final boolean nodeTypeSpecified;
    private final int types;

    private NodeMonitorOptions(boolean infoMap, boolean connectionId, boolean nodedownReason,
        boolean nodeTypeSpecified, int types) {
        this.infoMap = infoMap;
        this.connectionId = connectionId;
        this.nodedownReason = nodedownReason;
        this.nodeTypeSpecified = nodeTypeSpecified;
        this.types = types;
    }

    /**
     * The subscription created by net_kernel:monitor_nodes/1 or by the
     * legacy process_flag(monitor_nodes, Boolean) call: visible nodes only,
     * with two-element messages.
     */
    public static NodeMonitorOptions legacy() {
        return new NodeMonitorOptions(false, false, false, false, VISIBLE);
    }

    /**
     * Parse the options argument of net_kernel:monitor_nodes/2.
     *
     * An empty list is the legacy subscription. Any map, including the
     * empty map, asks for the three-element message form.
     *
     * @throws IllegalArgumentException when the options are not valid
     */
    public static NodeMonitorOptions parse(Term options) {
        if (options instanceof List) {
            List list = options.toList();
            if (list.length() == 0) {
                return legacy();
            }
            boolean connectionId = false;
            boolean nodedownReason = false;
            boolean nodeTypeSpecified = false;
            int types = VISIBLE;
            while (list.length() > 0) {
                Term option = list.head();
                if (CONNECTION_ID.equals(option)) {
                    connectionId = true;
                } else if (NODEDOWN_REASON.equals(option)) {
                    nodedownReason = true;
                } else if (option instanceof Tuple && option.toTuple().arity() == 2
                    && NODE_TYPE.equals(option.toTuple().element(1))) {
                    int parsed = parseNodeType(option.toTuple().element(2));
                    if (nodeTypeSpecified && types != parsed) {
                        throw new IllegalArgumentException("conflicting node_type options");
                    }
                    nodeTypeSpecified = true;
                    types = parsed;
                } else {
                    throw new IllegalArgumentException("invalid monitor_nodes option: " + option);
                }
                list = list.tail();
            }
            return new NodeMonitorOptions(false, connectionId, nodedownReason,
                nodeTypeSpecified, types);
        }

        if (options instanceof Map) {
            Map map = options.toMap();
            boolean connectionId = false;
            boolean nodedownReason = false;
            boolean nodeTypeSpecified = false;
            int types = VISIBLE;
            List keys = map.keys();
            while (keys.length() > 0) {
                Term key = keys.head();
                Term value = map.get(key);
                if (CONNECTION_ID.equals(key)) {
                    connectionId = parseBoolean(value, CONNECTION_ID);
                } else if (NODEDOWN_REASON.equals(key)) {
                    nodedownReason = parseBoolean(value, NODEDOWN_REASON);
                } else if (NODE_TYPE.equals(key)) {
                    nodeTypeSpecified = true;
                    types = parseNodeType(value);
                } else {
                    throw new IllegalArgumentException("invalid monitor_nodes option: " + key);
                }
                keys = keys.tail();
            }
            return new NodeMonitorOptions(true, connectionId, nodedownReason,
                nodeTypeSpecified, types);
        }

        throw new IllegalArgumentException("invalid monitor_nodes options: " + options);
    }

    private static boolean parseBoolean(Term value, Atom option) {
        if (Boolean.am_true.equals(value)) {
            return true;
        }
        if (Boolean.am_false.equals(value)) {
            return false;
        }
        throw new IllegalArgumentException(option + " must be a boolean: " + value);
    }

    private static int parseNodeType(Term value) {
        if (VISIBLE_ATOM.equals(value)) {
            return VISIBLE;
        }
        if (HIDDEN_ATOM.equals(value)) {
            return HIDDEN;
        }
        if (ALL_ATOM.equals(value)) {
            return ALL;
        }
        throw new IllegalArgumentException("invalid node_type: " + value);
    }

    /**
     * Whether this subscription includes events for the given node type.
     */
    public boolean includes(boolean hidden) {
        return (types & (hidden ? HIDDEN : VISIBLE)) != 0;
    }

    /**
     * Build the message this subscription receives for a node event.
     *
     * The info is a map when the caller passed a map and a list when it
     * passed a list. Its order matches OTP's builder: connection_id,
     * nodedown_reason, node_type.
     */
    public Term message(Term event, Atom node, boolean hidden, int connectionId, Term reason) {
        if (!infoMap && !hasInfo()) {
            return Tuple.of(event, node);
        }
        Atom nodeType = hidden ? HIDDEN_ATOM : VISIBLE_ATOM;
        boolean down = Atom.of("nodedown").equals(event);
        if (infoMap) {
            Map info = new Map();
            if (this.connectionId) {
                info.set(CONNECTION_ID, Integer.of(connectionId));
            }
            if (nodeTypeSpecified) {
                info.set(NODE_TYPE, nodeType);
            }
            if (down && nodedownReason) {
                info.set(NODEDOWN_REASON, reason);
            }
            return Tuple.of(event, node, info);
        }
        List info = List.nil;
        if (nodeTypeSpecified) {
            info = new List(Tuple.of(NODE_TYPE, nodeType), info);
        }
        if (down && nodedownReason) {
            info = new List(Tuple.of(NODEDOWN_REASON, reason), info);
        }
        if (this.connectionId) {
            info = new List(Tuple.of(CONNECTION_ID, Integer.of(connectionId)), info);
        }
        return Tuple.of(event, node, info);
    }

    /**
     * Whether this subscription asks for an info term at all. An empty map
     * asks for an empty info map; an empty list asks for no info.
     */
    private boolean hasInfo() {
        return infoMap || connectionId || nodedownReason || nodeTypeSpecified;
    }

    @Override
    public boolean equals(Object object) {
        if (!(object instanceof NodeMonitorOptions)) {
            return false;
        }
        NodeMonitorOptions other = (NodeMonitorOptions) object;
        return infoMap == other.infoMap
            && connectionId == other.connectionId
            && nodedownReason == other.nodedownReason
            && nodeTypeSpecified == other.nodeTypeSpecified
            && types == other.types;
    }

    @Override
    public int hashCode() {
        int result = infoMap ? 1 : 0;
        result = 31 * result + (connectionId ? 1 : 0);
        result = 31 * result + (nodedownReason ? 1 : 0);
        result = 31 * result + (nodeTypeSpecified ? 1 : 0);
        result = 31 * result + types;
        return result;
    }

    @Override
    public String toString() {
        return "NodeMonitorOptions[infoMap=" + infoMap
            + ", connectionId=" + connectionId
            + ", nodedownReason=" + nodedownReason
            + ", nodeTypeSpecified=" + nodeTypeSpecified
            + ", types=" + types + "]";
    }

}
