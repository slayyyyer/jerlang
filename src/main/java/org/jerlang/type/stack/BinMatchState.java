package org.jerlang.type.stack;

import java.math.BigInteger;

import org.jerlang.erts.erlang.Error;
import org.jerlang.type.Binary;
import org.jerlang.type.BitString;
import org.jerlang.type.Float;
import org.jerlang.type.Integer;
import org.jerlang.type.List;
import org.jerlang.type.Term;

/**
 * See:
 * https://github.com/erlang/otp/blob/maint/erts/emulator/beam/erl_bits.h
 */
public class BinMatchState extends Term {

    private final BitString bitString;
    private int offset = 0; // bit-offset
    private final int[] save_offset;

    public BinMatchState(BitString binary, int slots) {
        this.bitString = binary;
        this.save_offset = new int[slots + 1];
    }

    /**
     * Copy constructor
     */
    public BinMatchState(BinMatchState bms) {
        this.bitString = new BitString(bms.bitString.bytes(), bms.bitString.unusedBits());
        this.offset = bms.offset;
        this.save_offset = new int[bms.save_offset.length];
        System.arraycopy(bms.save_offset, 0, this.save_offset, 0, bms.save_offset.length);
    }

    public int tail() {
        return bitString.bits() - offset;
    }

    /**
     * The current position in the match, in bits. A match context is a value
     * the compiler can save and restore around a match, so the position has
     * to survive as a term; bs_get_position writes it to a register.
     */
    public int position() {
        return offset;
    }

    public void setPosition(int position) {
        if (position < 0 || position > bitString.bits()) {
            throw new Error("Invalid match position " + position);
        }
        offset = position;
    }

    /**
     * `ensure_at_least` checks both the number of bits left and that the
     * remainder is a whole number of units. A binary tail (unit 8) can not
     * start in the middle of a byte, which is why a 12 bit input does not
     * match `<<A:8, B/binary>>`.
     */
    public boolean ensureAtLeast(int bits, int unit) {
        int available = tail();
        return bits >= 0 && available >= bits && (unit <= 1 || available % unit == 0);
    }

    public boolean ensureExactly(int bits) {
        return bits >= 0 && tail() == bits;
    }

    public boolean skip(int bits) {
        if (bits < 0 || tail() < bits) {
            return false;
        }
        offset += bits;
        return true;
    }

    /**
     * Read an integer field of `bits` bits. Returns null when the field does
     * not fit, so the caller can take the fail label of bs_match.
     */
    public Integer readInteger(int bits, boolean signed, boolean little) {
        if (bits <= 0 || tail() < bits) {
            return null;
        }

        BigInteger value;
        if (little) {
            if (bits % 8 != 0) {
                throw new Error("Little endian fields of " + bits
                    + " bits are not supported");
            }
            value = BigInteger.ZERO;
            for (int done = 0; done < bits; done += 8) {
                int b = readByte(offset + done);
                if (b != 0) {
                    value = value.or(BigInteger.valueOf(b).shiftLeft(done));
                }
            }
        } else {
            value = readBits(offset, bits);
        }

        if (signed && value.testBit(bits - 1)) {
            value = value.subtract(BigInteger.ONE.shiftLeft(bits));
        }

        offset += bits;
        return new Integer(value);
    }

    /**
     * Read a fixed size binary (bits is a multiple of 8) or bitstring.
     */
    public Term readBinary(int bits) {
        if (bits < 0 || tail() < bits) {
            return null;
        }
        Term result = slice(offset, bits);
        offset += bits;
        return result;
    }

    /**
     * Take everything that is left. A tail that is not a whole number of
     * units can not be captured as a binary of that unit.
     */
    public Term readTail(int unit) {
        int bits = tail();
        if (unit > 1 && bits % unit != 0) {
            return null;
        }
        Term result = slice(offset, bits);
        offset += bits;
        return result;
    }

    public Float readFloat(int bits, boolean little) {
        if (bits != 32 && bits != 64) {
            throw new Error("Unsupported float size: " + bits);
        }
        if (tail() < bits) {
            return null;
        }
        long value = readBits(offset, bits).longValue();
        offset += bits;
        if (bits == 32) {
            int raw = (int) value;
            if (little) {
                raw = java.lang.Integer.reverseBytes(raw);
            }
            return new Float(java.lang.Float.intBitsToFloat(raw));
        }
        if (little) {
            value = java.lang.Long.reverseBytes(value);
        }
        return new Float(java.lang.Double.longBitsToDouble(value));
    }

    /**
     * Compare the next bits with a literal, without consuming them unless
     * they match.
     */
    public boolean matchLiteral(int bits, BigInteger expected) {
        if (bits < 0 || tail() < bits) {
            return false;
        }
        if (!readBits(offset, bits).equals(expected)) {
            return false;
        }
        offset += bits;
        return true;
    }

    private Term slice(int start, int bits) {
        if (bits % 8 == 0) {
            int[] bytes = new int[bits / 8];
            for (int index = 0; index < bytes.length; index++) {
                bytes[index] = readByte(start + (index * 8));
            }
            return new Binary(bytes);
        }
        int[] bytes = new int[(bits + 7) / 8];
        for (int index = 0; index < bits; index++) {
            if (bitAt(start + index)) {
                bytes[index / 8] |= 1 << (7 - (index % 8));
            }
        }
        return new BitString(bytes, 8 - (bits % 8));
    }

    private int readByte(int bitOffset) {
        if (bitOffset % 8 != 0) {
            throw new Error("Not byte aligned: " + bitOffset);
        }
        return bitString.bytes()[bitOffset / 8] & 0xFF;
    }

    private boolean bitAt(int bitOffset) {
        int b = bitString.bytes()[bitOffset / 8];
        return ((b >> (7 - (bitOffset % 8))) & 1) == 1;
    }

    private BigInteger readBits(int start, int bits) {
        BigInteger value = BigInteger.ZERO;
        for (int index = 0; index < bits; index++) {
            value = value.shiftLeft(1);
            if (bitAt(start + index)) {
                value = value.or(BigInteger.ONE);
            }
        }
        return value;
    }

    public BinMatchState toBinMatchState() {
        return this;
    }

    @Override
    public BitString toBitString() {
        return bitString;
    }

    @Override
    public String toString() {
        return "BinMatchState";
    }

    public BitString get_all_binary(int unit, int flag) {
        // TODO This assumes that unit = 1 and flag = 0
        BitString result = bitString.get_rest(offset);
        offset = bitString.bits();
        return result;
    }

    public Float get_float(int size, int unit, int flag) {
        // TODO: This assumes size = 64, unit = 1 and flag = 0.
        Integer integer = get_integer(size, unit, flag);
        return new Float(Double.longBitsToDouble(integer.toLong()));
    }

    public Integer get_integer(int size, int unit, int flag) {
        // TODO: This assumes that unit is 1 and flag is 0.

        if (tail() - size < 0) {
            return null;
        }

        Integer result = new Integer(bitString.extract_bits(offset, size));
        offset += size;
        return result;
    }

    public void skip(int size, int unit, int flag) {
        offset += size * unit;
    }

    public boolean skip_utf8(int size, int flag) {
        // TODO: Implement UTF8 decoding
        if (tail() - 8 < 0) {
            return false;
        } else {
            offset += 8;
            return true;
        }
    }

    public boolean skip_utf16(int size, int flag) {
        // TODO: Implement UTF16 decoding
        if (tail() - 16 < 0) {
            return false;
        } else {
            offset += 16;
            return true;
        }
    }

    public boolean skip_utf32(int size, int flag) {
        if (tail() - 32 < 0) {
            return false;
        } else {
            offset += 32;
            return true;
        }
    }

    public Term get_utf8(int size, int flag) {
        // TODO: Implement UTF8 decoding
        if (tail() - 8 < 0) {
            return List.nil;
        } else {
            int value = bitString.extract_bits(offset, 8).intValue() & 0xFF;
            offset += 8;
            return Integer.of(value);
        }
    }

    public Term get_utf16(int size, int flag) {
        // TODO: Implement UTF16 decoding
        if (tail() - 16 < 0) {
            return List.nil;
        } else {
            int value = bitString.extract_bits(offset, 16).intValue() & 0xFFFF;
            offset += 16;
            return Integer.of(value);
        }
    }

    public Term get_utf32(int size, int flag) {
        // TODO: Implement UTF32 decoding
        if (tail() - 32 < 0) {
            return List.nil;
        } else {
            long value = bitString.extract_bits(offset, 32).longValue() & 0xFFFFFFFF;
            offset += 32;
            return new Integer(value);
        }
    }

    public boolean test_unit(int unit) {
        return (tail() % unit) == 0;
    }

    public boolean match(BitString pattern) {
        if (offset % 8 != 0) {
            throw new Error("Not implemented");
        }
        if (pattern.bits() % 8 != 0) {
            throw new Error("Not implemented");
        }
        if (tail() < pattern.bits()) {
            return false;
        }
        // Compare bytes
        int nbytes = pattern.bits() / 8;
        int boffset = offset / 8;
        for (int i = 0; i < nbytes; i++) {
            if (bitString.bytes()[boffset + i] != pattern.bytes()[i]) {
                return false;
            }
        }
        offset += (nbytes * 8);
        // Bits
        // TODO: Compare bits
        return true;
    }

    public void restore(int index) {
        offset = save_offset[index];
    }

    public void save(int index) {
        save_offset[index] = offset;
    }

}
