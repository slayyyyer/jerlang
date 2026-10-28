package org.jerlang.type;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;

import org.jerlang.erts.Erlang;
import org.jerlang.vm.VirtualMachine;
import org.junit.After;
import org.junit.Test;

public class PIDTest {

    private static final Atom NODE = Atom.of("nonode@nohost");

    @After
    public void restoreNode() {
        VirtualMachine.instance().setNode(NODE, 0);
    }

    @Test
    public void localPidIsPrintedWithoutItsNode() {
        assertEquals("<0.7.0>", new PID(7).toString());
    }

    @Test
    public void localPidBelongsToTheVirtualMachineNode() {
        PID pid = new PID(7);
        assertEquals(NODE, pid.node());
        assertEquals(0, pid.creation());
    }

    @Test
    public void remotePidIsPrintedWithItsNodeAndSerial() {
        PID pid = new PID(Atom.of("other@host"), 7, 3, 1);
        assertEquals("<other@host.7.3>", pid.toString());
    }

    @Test
    public void pidsDifferByNodeSerialAndCreation() {
        PID pid = new PID(NODE, 7, 0, 0);
        assertEquals(pid, new PID(NODE, 7, 0, 0));
        assertNotEquals(pid, new PID(Atom.of("other@host"), 7, 0, 0));
        assertNotEquals(pid, new PID(NODE, 7, 1, 0));
        assertNotEquals(pid, new PID(NODE, 7, 0, 1));
    }

    @Test
    public void newPidsUseTheCurrentNodeName() {
        VirtualMachine.instance().setNode(Atom.of("renamed@host"), 2);
        PID pid = new PID(9);
        assertEquals(Atom.of("renamed@host"), pid.node());
        assertEquals(2, pid.creation());
        assertEquals("<0.9.0>", pid.toString());
    }

    @Test
    public void nodeOneReturnsTheNodeOfAPidAndAReference() {
        assertEquals(Atom.of("other@host"),
            Erlang.node(new PID(Atom.of("other@host"), 1, 0, 0)));
        assertEquals(Atom.of("other@host"),
            Erlang.node(new Reference(Atom.of("other@host"), 1, 0)));
        assertEquals(NODE, Erlang.node(new PID(1)));
    }

    @Test
    public void referencesCarryTheirNodeAndCreation() {
        Reference reference = new Reference(Atom.of("other@host"), 42, 3);
        assertEquals("#Ref<other@host.42.3>", reference.toString());
        assertNotEquals(reference, new Reference(Atom.of("other@host"), 42, 2));
    }

}
