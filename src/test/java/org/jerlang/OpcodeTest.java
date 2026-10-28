package org.jerlang;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class OpcodeTest {

    @Test
    public void testEncode() {
        assertEquals(1, Opcode.label.encode());
    }

    @Test
    public void testDecode() {
        assertEquals(Opcode.label, Opcode.decode(1));
        for (Opcode opcode : Opcode.values()) {
            assertEquals(opcode, Opcode.decode(opcode.encode()));
        }
    }

    @Test(expected = Error.class)
    public void testDecodeUnsupportedOpcode() {
        Opcode.decode(Opcode.max() + 1);
    }

    @Test
    public void testToString() {
        assertEquals("label", Opcode.label.toString());
        assertEquals("try", Opcode._try.toString());
    }

}
