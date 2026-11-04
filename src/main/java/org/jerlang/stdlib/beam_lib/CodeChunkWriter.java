package org.jerlang.stdlib.beam_lib;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.IOException;

import org.jerlang.Opcode;

/**
 * Writes the {@code Code} chunk: a five word header followed by the
 * instructions, ending at {@code int_code_end}.
 *
 * The header is copied because nothing in it is derived: the instruction set
 * version and the highest opcode are facts about the compiler that produced the
 * file, and recomputing them from the instructions would turn a preserved fact
 * into a guess. The instructions are re-encoded operand by operand through
 * {@link BeamOperand}, which is what makes this a writer rather than a copy.
 *
 * A few instructions may follow {@code int_code_end}; the loader stops there,
 * so they are copied rather than dropped. Dropping them would change the
 * chunk's length and therefore the module's digest.
 */
public final class CodeChunkWriter {

    private static final int HEADER_SIZE = 20;

    private CodeChunkWriter() {
    }

    public static byte[] write(byte[] payload, AtomChunk atoms, LiteralTableChunk literals)
        throws IOException {
        if (payload.length < HEADER_SIZE) {
            throw new IllegalArgumentException("code chunk is " + payload.length
                + " bytes, shorter than its " + HEADER_SIZE + " byte header");
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream(payload.length);
        out.write(payload, 0, HEADER_SIZE);
        DataInputStream in = new DataInputStream(
            new ByteArrayInputStream(payload, HEADER_SIZE, payload.length - HEADER_SIZE));
        OperandReader reader = new OperandReader(in);
        while (true) {
            int opcode = reader.read1Byte();
            out.write(opcode);
            Opcode instruction = Opcode.decode(opcode);
            if (instruction == Opcode.int_code_end) {
                break;
            }
            for (int argument = 0; argument < instruction.arity(); argument++) {
                BeamOperand operand = reader.read(atoms, literals);
                operand.writeTo(out);
            }
        }
        byte[] trailing = new byte[in.available()];
        in.readFully(trailing);
        out.write(trailing, 0, trailing.length);
        return out.toByteArray();
    }

}
