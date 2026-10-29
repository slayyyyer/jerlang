package org.jerlang.erts;

import static org.assertj.core.api.AssertionsForClassTypes.assertThat;

import org.jerlang.FunctionSignature;
import org.jerlang.type.Atom;
import org.jerlang.vm.VirtualMachine;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

public class EmulatorTest {

    private Atom previousNode;
    private int previousCreation;

    @Before
    public void saveNodeIdentity() {
        previousNode = VirtualMachine.instance().node();
        previousCreation = VirtualMachine.instance().nodeCreation();
    }

    @After
    public void restoreNodeIdentity() {
        // -sname renames the shared virtual machine. Other tests expect
        // the default nonode@nohost, so undo it here.
        VirtualMachine.instance().setNode(previousNode, previousCreation);
    }

    @Test
    public void testParse() {
        Emulator.parse("+W w -sname arnie +R 9 -s my_init -extra +bertie".split(" "));
        assertThat(Runtime.getRunFlag())
            .isEqualTo(new FunctionSignature("my_init", "start", 0));
    }

}
