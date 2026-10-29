package org.jerlang.type;

import java.math.BigInteger;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;

import org.jerlang.vm.VirtualMachine;

/**
 * References are Erlang objects with exactly two properties:
 *
 * * They can be created by a program (using make_ref/0), and,
 * * They can be compared for equality.
 *
 * Erlang references are unique, the system guarantees that no two
 * references created by different calls to make_ref will ever match.
 *
 * Like a PID, a reference is only unique together with the node that
 * created it and that node's creation number. Erlang/OTP 26 and later
 * store the ID as a sequence of up to three big-endian 32 bit words,
 * which is why it is kept as a BigInteger here.
 *
 * Source: http://www.erlang.org/course/advanced.html#refs
 */
public class Reference extends Term {

    private static final AtomicLong counter = new AtomicLong();

    private final Atom node;
    private final BigInteger id;
    private final int creation;

    /**
     * A reference made on this virtual machine.
     */
    public Reference() {
        this(VirtualMachine.instance().node(), BigInteger.valueOf(counter.incrementAndGet()),
            VirtualMachine.instance().nodeCreation());
    }

    public Reference(Atom node, BigInteger id, int creation) {
        this.node = node;
        this.id = id;
        this.creation = creation;
    }

    public Reference(Atom node, long id, int creation) {
        this(node, BigInteger.valueOf(id), creation);
    }

    public Atom node() {
        return node;
    }

    public BigInteger id() {
        return id;
    }

    public int creation() {
        return creation;
    }

    @Override
    public boolean equals(Object object) {
        if (object instanceof Reference) {
            Reference other = (Reference) object;
            return node.equals(other.node) && id.equals(other.id) && creation == other.creation;
        }
        return false;
    }

    @Override
    public int hashCode() {
        return Objects.hash(node, id, creation);
    }

    @Override
    public Reference toReference() {
        return this;
    }

    @Override
    public String toString() {
        if (node.equals(VirtualMachine.instance().node())
            && creation == VirtualMachine.instance().nodeCreation()) {
            return "#Ref<0." + id + ".0>";
        }
        return "#Ref<" + node + "." + id + "." + creation + ">";
    }

}
