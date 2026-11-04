package org.jerlang.stdlib.beam_lib;

import java.io.ByteArrayOutputStream;
import java.math.BigInteger;

/**
 * Big-endian integers and the compact operand encoding of the code chunk.
 *
 * The compact encoding is defined by {@code lib/compiler/src/beam_asm.erl} and
 * is decoded by {@link AbstractReader#decodeInt(int, org.jerlang.OpcodeTag)};
 * this class is the other direction. It is used by the chunk writers and by
 * {@link BeamOperand}, so a value is never encoded two different ways in the
 * same file.
 */
public final class BeamBytes {

    private static final int EXTENDED = 0x08;
    private static final int BIGNUM = 0x18;

    private BeamBytes() {
    }

    public static void write4(ByteArrayOutputStream out, int value) {
        out.write((value >>> 24) & 0xFF);
        out.write((value >>> 16) & 0xFF);
        out.write((value >>> 8) & 0xFF);
        out.write(value & 0xFF);
    }

    public static int read4(byte[] bytes, int offset) {
        return ((bytes[offset] & 0xFF) << 24)
            | ((bytes[offset + 1] & 0xFF) << 16)
            | ((bytes[offset + 2] & 0xFF) << 8)
            | (bytes[offset + 3] & 0xFF);
    }

    /**
     * The shortest form of a non-negative value: four bits, eleven bits, or a
     * bignum. The tag occupies the low three bits of the first byte.
     */
    public static void writeCompact(ByteArrayOutputStream out, long value, int tag) {
        if (value >= 0 && value < 16) {
            out.write(((int) value << 4) | tag);
        } else if (value >= 0 && value < 2048) {
            out.write((((int) value >> 8) << 5) | EXTENDED | tag);
            out.write((int) value & 0xFF);
        } else {
            writeBignum(out, BigInteger.valueOf(value), minimalWidth(BigInteger.valueOf(value), false),
                tag, false);
        }
    }

    /**
     * Encodes an integer that was read in a known form. The form is kept by
     * {@link BeamOperand} because the compiler is not guaranteed to pick the
     * shortest one, and a module is only identical to itself if every byte is.
     */
    public static void writeInteger(ByteArrayOutputStream out, int form, BigInteger value,
        int width, int tag, boolean unsigned) {
        switch (form) {
        case BeamOperand.FOUR_BIT:
            out.write((value.intValueExact() << 4) | tag);
            return;
        case BeamOperand.ELEVEN_BIT:
            int small = value.intValueExact();
            out.write(((small >> 8) << 5) | EXTENDED | tag);
            out.write(small & 0xFF);
            return;
        default:
            writeBignum(out, value, width, tag, unsigned);
        }
    }

    private static void writeBignum(ByteArrayOutputStream out, BigInteger value, int width,
        int tag, boolean unsigned) {
        if (width < 2) {
            throw new IllegalArgumentException("bignum width " + width + " is shorter than two bytes");
        }
        int code = width <= 8 ? width - 2 : 7;
        out.write((code << 5) | BIGNUM | tag);
        if (width > 8) {
            // A length of seven means the real length follows as a compact
            // integer, offset by nine so that it can not collide with the
            // lengths the three bit field holds directly.
            writeCompact(out, width - 9, 0);
        }
        BigInteger modulus = BigInteger.ONE.shiftLeft(width * 8);
        BigInteger encoded = !unsigned && value.signum() < 0 ? value.add(modulus) : value;
        for (int shift = (width - 1) * 8; shift >= 0; shift -= 8) {
            out.write(encoded.shiftRight(shift).intValue() & 0xFF);
        }
    }

    private static int minimalWidth(BigInteger value, boolean unsigned) {
        if (!unsigned && value.signum() < 0) {
            int bits = value.bitLength() + 1;
            return Math.max(2, (bits + 7) / 8);
        }
        int bits = value.bitLength();
        return Math.max(2, (bits + 7) / 8);
    }

    public static byte[] encodeCompact(long value, int tag) {
        ByteArrayOutputStream out = new ByteArrayOutputStream(2);
        writeCompact(out, value, tag);
        return out.toByteArray();
    }

}
