package org.jerlang.type;

import java.util.Objects;

import org.jerlang.vm.VirtualMachine;

/**
 * The identifier of a port.
 *
 * A port belongs to a node, and the creation number distinguishes
 * incarnations of that node, exactly like a PID. Ports from another node
 * can be carried in a term, but this node can not operate on them.
 *
 * See:
 * http://erlang.org/doc/reference_manual/ports.html
 */
public class PortID extends PidOrPortId {

    private final Atom node;
    private final int processId;
    private final int creation;

    /**
     * A port on this virtual machine.
     */
    public PortID(int processId) {
        this(VirtualMachine.instance().node(), processId,
            VirtualMachine.instance().nodeCreation());
    }

    public PortID(Atom node, int processId, int creation) {
        this.node = node;
        this.processId = processId;
        this.creation = creation;
    }

    public Atom node() {
        return node;
    }

    public int processId() {
        return processId;
    }

    public int creation() {
        return creation;
    }

    /**
     * True if this port belongs to the current incarnation of this node.
     */
    public boolean isLocal() {
        VirtualMachine vm = VirtualMachine.instance();
        return node.equals(vm.node()) && creation == vm.nodeCreation();
    }

    @Override
    public boolean equals(Object object) {
        if (object instanceof PortID) {
            PortID other = (PortID) object;
            return node.equals(other.node)
                && processId == other.processId
                && creation == other.creation;
        }
        return false;
    }

    @Override
    public int hashCode() {
        return Objects.hash(node, processId, creation);
    }

    @Override
    public PortID toPortID() {
        return this;
    }

    @Override
    public String toString() {
        if (isLocal()) {
            return "#Port<0." + processId + ">";
        }
        return "#Port<" + node + "." + processId + ">";
    }

}
