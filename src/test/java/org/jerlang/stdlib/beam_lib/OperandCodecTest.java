package org.jerlang.stdlib.beam_lib;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.nio.file.Path;
import java.util.Arrays;

import org.jerlang.Opcode;
import org.junit.Test;

/**
 * A1.2: the operand codec.
 *
 * Every operand of every instruction in the corpus is read, encoded again, and
 * compared with the bytes it came from. The comparison is against the file, not
 * against a second run of the decoder, so a mistake the reader and the writer
 * share can not make the test pass: the file was written by {@code erlc}.
 */
public class OperandCodecTest {

    @Test
    public void everyOperandReencodesToItsOriginalBytes() throws Throwable {
        int operands = 0;
        int[] forms = new int[3];
        int[] extended = new int[8];
        for (Path path : Fixtures.paths()) {
            byte[] bytes = Fixtures.bytes(path);
            BeamFile file = BeamFile.read(bytes);
            if (!file.has("Code") || !file.has("LitT")) {
                continue;
            }
            AtomChunk atoms = readAtoms(file);
            LiteralTableChunk literals = new LiteralTableChunkReader(
                new Chunk(ChunkId.LITT, 0, file.payload("LitT").length),
                new DataInputStream(new ByteArrayInputStream(file.payload("LitT")))).read();
            byte[] code = file.payload("Code");
            int position = 20;
            while (true) {
                int opcode = code[position++] & 0xFF;
                Opcode instruction = Opcode.decode(opcode);
                if (instruction == Opcode.int_code_end) {
                    break;
                }
                for (int argument = 0; argument < instruction.arity(); argument++) {
                    int before = code.length - position;
                    DataInputStream in = new DataInputStream(
                        new ByteArrayInputStream(code, position, before));
                    BeamOperand operand = new OperandReader(in).read(atoms, literals);
                    int consumed = before - in.available();
                    assertTrue(path + " read no bytes at " + position, consumed > 0);

                    ByteArrayOutputStream encoded = new ByteArrayOutputStream();
                    operand.writeTo(encoded);
                    assertArrayEquals(path + " operand at " + position,
                        Arrays.copyOfRange(code, position, position + consumed),
                        encoded.toByteArray());

                    position += consumed;
                    operands++;
                    forms[operand.form()]++;
                    if (operand.tag() == 7) {
                        extended[operand.extendedTag()]++;
                    }
                }
            }
        }
        assertTrue("expected the corpus to exercise the codec, saw " + operands, operands > 5000);
        // The point of keeping the form is that the corpus really contains more
        // than the shortest encoding of each number.
        assertTrue("no four bit operands", forms[BeamOperand.FOUR_BIT] > 0);
        assertTrue("no eleven bit operands", forms[BeamOperand.ELEVEN_BIT] > 0);
        assertTrue("no bignum operands", forms[BeamOperand.BIGNUM] > 0);
    }

    private static AtomChunk readAtoms(BeamFile file) throws Throwable {
        if (file.has("Atom")) {
            return new AtomChunkReader(new Chunk(ChunkId.ATOM, 0, file.payload("Atom").length),
                new DataInputStream(new ByteArrayInputStream(file.payload("Atom")))).read();
        }
        return new AtomChunkReader(new Chunk(ChunkId.ATU8, 0, file.payload("AtU8").length),
            new DataInputStream(new ByteArrayInputStream(file.payload("AtU8"))), true).read();
    }

}
