package org.jerlang.erts.emulator.op;

import java.io.IOException;
import java.math.BigInteger;

import org.jerlang.Module;
import org.jerlang.Process;
import org.jerlang.erts.emulator.Instruction;
import org.jerlang.erts.erlang.Error;
import org.jerlang.type.Atom;
import org.jerlang.type.BitString;
import org.jerlang.type.BitStringOutputStream;
import org.jerlang.type.Float;
import org.jerlang.type.Integer;
import org.jerlang.type.List;
import org.jerlang.type.Term;

/**
 * Build a binary using the binary syntax.
 *
 * Arguments:
 * 1. Fail label
 * 2. Allocation list (ignored, the JVM allocates)
 * 3. Live registers (ignored)
 * 4. Unit of the whole operation
 * 5. Destination
 * 6. A flat list of six-element segments:
 *    [Type, Seg, Unit, Flags, Value, Size]
 *
 * Example:
 * <pre>{@code
 * {bs_create_bin,{f,0},0,2,8,{x,0},
 *   {list,[{atom,string},0,8,nil,{string,<<1>>},{integer,1},
 *          {atom,integer},1,1,nil,{x,0},{integer,16}]}}
 * }</pre>
 */
public class BsCreateBin {

    private static final Atom LITTLE = Atom.of("little");
    private static final Atom ALL = Atom.of("all");

    public static Term execute(Process proc, Module m, Instruction i, List params) {
        Term destination = i.arg(4);
        List operations = i.arg(5).toArg(proc).toList();
        BitStringOutputStream out = new BitStringOutputStream();

        while (operations.length() > 0) {
            if (operations.length() < 6) {
                throw Error.badarg;
            }
            Atom type = operations.head().toArg(proc).toAtom();
            // Segment count (operations.tail().head()) is only an
            // allocation hint, as in OTP's bs_create_bin.
            int unit = operations.tail().tail().head().toArg(proc).toInteger().toInt();
            List flags = operations.tail().tail().tail().head().toArg(proc).toList();
            Term value = operations.tail().tail().tail().tail().head().toArg(proc);
            Term size = operations.tail().tail().tail().tail().tail().head().toArg(proc);

            boolean little = hasFlag(flags, LITTLE);

            try {
                switch (type.toString()) {
                case "integer":
                    writeInteger(out, value.toInteger(), size, unit, little);
                    break;
                case "float":
                    writeFloat(out, value.toFloat(), size, little);
                    break;
                case "binary":
                case "bits":
                case "append":
                    writeBinary(out, value.toBitString(), size, unit);
                    break;
                case "string":
                    // A string segment is an offset into the module's string
                    // table, not the bytes themselves (beam_asm encodes
                    // {string,Bin} as the table offset).
                    writeString(out, m, value.toInteger(), size);
                    break;
                case "utf8":
                    writeUtf8(out, value.toInteger());
                    break;
                case "utf16":
                    writeUtf16(out, value.toInteger(), little);
                    break;
                case "utf32":
                    writeUtf32(out, value.toInteger(), little);
                    break;
                default:
                    throw new Error("Unsupported bs_create_bin segment type: " + type);
                }
            } catch (IOException e) {
                throw new Error("Can not build binary: " + e.getMessage());
            }

            operations = operations.tail().tail().tail().tail().tail().tail();
        }

        setRegister(proc, destination, out.toBitString());
        return null;
    }

    private static void writeInteger(BitStringOutputStream out, Integer value, Term size,
        int unit, boolean little) throws IOException {
        if (!(size instanceof Integer)) {
            throw new Error("Unsupported integer size: " + size);
        }
        int bits = size.toInteger().toInt() * unit;
        if (bits <= 0) {
            throw Error.badarg;
        }
        BigInteger raw = value.toBigInteger().mod(BigInteger.ONE.shiftLeft(bits));
        if (little) {
            if (bits % 8 != 0) {
                throw new Error("Little endian fields of " + bits + " bits are not supported");
            }
            for (int written = 0; written < bits; written += 8) {
                out.writeBits(raw.shiftRight(written).intValue() & 0xFF, 8);
            }
        } else {
            for (int bit = bits - 1; bit >= 0; bit--) {
                out.writeBit(raw.testBit(bit) ? 1 : 0);
            }
        }
    }

    private static void writeFloat(BitStringOutputStream out, Float value, Term size,
        boolean little) throws IOException {
        int bits = size instanceof Integer ? size.toInteger().toInt() : 64;
        long raw;
        if (bits == 32) {
            raw = java.lang.Float.floatToRawIntBits((float) value.toBigDecimal().doubleValue()) & 0xFFFFFFFFL;
        } else if (bits == 64) {
            raw = java.lang.Double.doubleToRawLongBits(value.toBigDecimal().doubleValue());
        } else {
            throw new Error("Unsupported float size: " + bits);
        }
        if (little) {
            for (int written = 0; written < bits; written += 8) {
                out.writeBits((int) (raw >>> written) & 0xFF, 8);
            }
        } else {
            for (int bit = bits - 1; bit >= 0; bit--) {
                out.writeBit(((raw >>> bit) & 1) == 1 ? 1 : 0);
            }
        }
    }

    private static void writeBinary(BitStringOutputStream out, BitString value, Term size,
        int unit) throws IOException {
        int bits;
        if (size instanceof Integer) {
            bits = size.toInteger().toInt() * unit;
            if (bits > value.bits()) {
                throw Error.badarg;
            }
            if (bits % 8 == 0 && unit % 8 == 0) {
                // A byte-aligned prefix can be written without splitting bits.
                int[] bytes = value.bytes();
                for (int index = 0; index < bits / 8; index++) {
                    out.writeBits(bytes[index], 8);
                }
                return;
            }
        } else if (size.equals(ALL)) {
            bits = value.bits();
            if (unit > 1 && bits % unit != 0) {
                throw Error.badarg;
            }
        } else {
            throw Error.badarg;
        }
        out.writeBitString(slice(value, bits));
    }

    private static void writeString(BitStringOutputStream out, Module m, Integer offset,
        Term size) throws IOException {
        if (!(size instanceof Integer)) {
            throw Error.badarg;
        }
        int length = size.toInteger().toInt();
        BitString strings = m.beamData().stringTableChunk().strings();
        int start = offset.toInt();
        if (length < 0 || start < 0 || start + length > strings.byte_length()) {
            throw Error.badarg;
        }
        for (int index = 0; index < length; index++) {
            out.writeBits(strings.bytes()[start + index], 8);
        }
    }

    private static void writeUtf8(BitStringOutputStream out, Integer value) throws IOException {
        int codePoint = codePoint(value);
        if (codePoint < 0x80) {
            out.writeBits(codePoint, 8);
        } else if (codePoint < 0x800) {
            out.writeBits(0xC0 | (codePoint >> 6), 8);
            out.writeBits(0x80 | (codePoint & 0x3F), 8);
        } else if (codePoint < 0x10000) {
            out.writeBits(0xE0 | (codePoint >> 12), 8);
            out.writeBits(0x80 | ((codePoint >> 6) & 0x3F), 8);
            out.writeBits(0x80 | (codePoint & 0x3F), 8);
        } else {
            out.writeBits(0xF0 | (codePoint >> 18), 8);
            out.writeBits(0x80 | ((codePoint >> 12) & 0x3F), 8);
            out.writeBits(0x80 | ((codePoint >> 6) & 0x3F), 8);
            out.writeBits(0x80 | (codePoint & 0x3F), 8);
        }
    }

    private static void writeUtf16(BitStringOutputStream out, Integer value, boolean little)
        throws IOException {
        int codePoint = codePoint(value);
        if (codePoint < 0x10000) {
            writeCodeUnit(out, codePoint, little);
        } else {
            int adjusted = codePoint - 0x10000;
            writeCodeUnit(out, 0xD800 | (adjusted >> 10), little);
            writeCodeUnit(out, 0xDC00 | (adjusted & 0x3FF), little);
        }
    }

    private static void writeUtf32(BitStringOutputStream out, Integer value, boolean little)
        throws IOException {
        int codePoint = codePoint(value);
        if (little) {
            for (int written = 0; written < 32; written += 8) {
                out.writeBits((codePoint >>> written) & 0xFF, 8);
            }
        } else {
            for (int bit = 31; bit >= 0; bit--) {
                out.writeBit(((codePoint >>> bit) & 1) == 1 ? 1 : 0);
            }
        }
    }

    private static void writeCodeUnit(BitStringOutputStream out, int codeUnit, boolean little)
        throws IOException {
        if (little) {
            out.writeBits(codeUnit & 0xFF, 8);
            out.writeBits((codeUnit >> 8) & 0xFF, 8);
        } else {
            out.writeBits((codeUnit >> 8) & 0xFF, 8);
            out.writeBits(codeUnit & 0xFF, 8);
        }
    }

    private static int codePoint(Integer value) {
        int codePoint = value.toInt();
        if (codePoint < 0 || codePoint > 0x10FFFF
            || (codePoint >= 0xD800 && codePoint <= 0xDFFF)) {
            throw Error.badarg;
        }
        return codePoint;
    }

    private static BitString slice(BitString value, int bits) {
        if (bits == value.bits()) {
            return value;
        }
        int[] bytes = new int[(bits + 7) / 8];
        for (int index = 0; index < bits; index++) {
            int source = value.bytes()[index / 8];
            if (((source >> (7 - (index % 8))) & 1) == 1) {
                bytes[index / 8] |= 1 << (7 - (index % 8));
            }
        }
        int unused = (8 - (bits % 8)) % 8;
        return new BitString(bytes, unused);
    }

    private static boolean hasFlag(List flags, Atom wanted) {
        while (flags.length() > 0) {
            if (flags.head().equals(wanted)) {
                return true;
            }
            flags = flags.tail();
        }
        return false;
    }

    private static void setRegister(Process proc, Term destination, Term value) {
        if (destination.isXRegister()) {
            proc.setX(destination.toRegisterIndex(), value);
        } else if (destination.isYRegister()) {
            proc.setY(destination.toRegisterIndex(), value);
        } else {
            throw new Error("Unsupported destination: " + destination);
        }
    }

}
