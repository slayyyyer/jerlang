package org.jerlang.type;

import java.util.Objects;

import org.jerlang.vm.VirtualMachine;

/**
 * A process identifier.
 *
 * A PID identifies a process on a node. The node is an atom, the
 * process ID and the serial number are local to that node, and the
 * creation number distinguishes incarnations of the same node name
 * after a restart.
 *
 * PIDs whose node is the node of this virtual machine are printed
 * without the node, exactly like Erlang/OTP prints local PIDs:
 * `<0.33.0>`. PIDs from another node are printed with the node:
 * `<other@host.33.0>`.
 *
 * See:
 * http://erlang.org/doc/reference_manual/data_types.html#id68224
 */
public class PID extends PidOrPortId {

    private final Atom node;
    private final int processId;
    private final int serial;
    private final int creation;

    /**
     * A PID on this virtual machine.
     */
    public PID(int processId) {
        this(VirtualMachine.instance().node(), processId, 0,
            VirtualMachine.instance().nodeCreation());
    }

    public PID(Atom node, int processId, int serial, int creation) {
        this.node = node;
        this.processId = processId;
        this.serial = serial;
        this.creation = creation;
    }

    public Atom node() {
        return node;
    }

    public int processId() {
        return processId;
    }

    public int serial() {
        return serial;
    }

    public int creation() {
        return creation;
    }

    /**
     * True if this PID belongs to the current incarnation of this node.
     */
    public boolean isLocal() {
        VirtualMachine vm = VirtualMachine.instance();
        return node.equals(vm.node()) && creation == vm.nodeCreation();
    }

    @Override
    public boolean equals(Object object) {
        if (object instanceof PID) {
            PID other = (PID) object;
            return node.equals(other.node)
                && processId == other.processId
                && serial == other.serial
                && creation == other.creation;
        }
        return false;
    }

    @Override
    public int hashCode() {
        return Objects.hash(node, processId, serial, creation);
    }

    @Override
    public PID toPID() {
        return this;
    }

    @Override
    public String toString() {
        if (isLocal()) {
            return "<0." + processId + "." + serial + ">";
        }
        return "<" + node + "." + processId + "." + serial + ">";
    }

}
