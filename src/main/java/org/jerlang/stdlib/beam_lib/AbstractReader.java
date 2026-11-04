package org.jerlang.stdlib.beam_lib;

import java.io.DataInputStream;
import java.io.IOException;
import java.math.BigInteger;

import org.jerlang.OpcodeTag;
import org.jerlang.type.Integer;
import org.jerlang.type.Term;

public class AbstractReader {

    private DataInputStream inputStream;

    public AbstractReader(DataInputStream inputStream) {
        this.inputStream = inputStream;
    }

    protected DataInputStream inputStream() {
        return inputStream;
    }

    protected int read1Byte() throws IOException {
        return inputStream.readUnsignedByte();
    }

    protected int read2Bytes() throws IOException {
        return inputStream.readUnsignedShort();
    }

    protected int read4Bytes() throws IOException {
        return inputStream.readInt();
    }

    protected long read8Bytes() throws IOException {
        return inputStream.readLong();
    }

    protected int readBytes(byte[] bytes) throws IOException {
        return inputStream.read(bytes);
    }

    protected void setInputStream(DataInputStream inputStream) {
        this.inputStream = inputStream;
    }

    /**
     * Reads one operand and returns the term the interpreter works with.
     *
     * The encoding is read by {@link OperandReader}, which keeps the tag and
     * the integer form alongside the term. The assembler needs those to write
     * the operand back; the interpreter only needs the term.
     */
    protected Term decodeArg(AtomChunk atomChunk, LiteralTableChunk literalTableChunk) throws IOException {
        return new OperandReader(inputStream).read(atomChunk, literalTableChunk).term();
    }

    protected Integer decodeInt(int b) throws IOException {
        return decodeInt(b, OpcodeTag.i);
    }

    protected Integer decodeInt(int b, OpcodeTag opcodeTag) throws IOException {
        // N < 16 = 4 bits, NNNN:0:TTT
        if ((b & 0x08) == 0) {
            return Integer.of(b >> 4);
        }

        // N < 2048 = 11 bits = 3:8 bits, NNN:01:TTT, NNNNNNNN
        if ((b & 0x10) == 0) {
            return Integer.of(((b & 0b11100000) << 3) | read1Byte());
        }

        // Bignum
        int numBytes = decodeIntLength(b);
        // The length is read out of the file, so it is checked against what the
        // file can still supply. Without this a mutated instruction asked for a
        // two gigabyte array, and then shifted a BigInteger left by
        // numBytes * 8 bits, which is not a slow load but a hang: the fuzzing
        // campaign spent 23 minutes of CPU in shiftLeft before it was killed.
        if (numBytes < 0 || numBytes > inputStream.available()) {
            throw new IllegalArgumentException("bignum length " + numBytes
                + " exceeds the " + inputStream.available() + " bytes left in the file");
        }
        byte[] bytes = new byte[numBytes];
        readBytes(bytes);

        BigInteger n = BigInteger.ZERO;
        for (byte x : bytes) {
            n = n.shiftLeft(8);
            n = n.or(BigInteger.valueOf(x & 0xFF));
        }

        // Negative number, stored as two's complement in numBytes bytes.
        if ((bytes[0] & 0x80) != 0 && opcodeTag == OpcodeTag.i) {
            n = n.subtract(BigInteger.ONE.shiftLeft(numBytes * 8));
        }

        return new Integer(n);
    }

    private int decodeIntLength(int b) throws IOException {
        int len = b >>> 5;
        if (len == 7) {
            Term term = decodeArg(null, null);
            if (term instanceof Integer) {
                return term.toInteger().toInt() + 9;
            } else {
                throw new Error("Weird bignum sublength");
            }
        } else {
            return len + 2;
        }
    }

}
