package org.jerlang.erts.etf;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;

import org.jerlang.erts.erlang.Error;
import org.jerlang.type.Atom;
import org.jerlang.type.Binary;
import org.jerlang.type.BitString;
import org.jerlang.type.Float;
import org.jerlang.type.ImproperList;
import org.jerlang.type.Integer;
import org.jerlang.type.List;
import org.jerlang.type.Map;
import org.jerlang.type.PID;
import org.jerlang.type.Reference;
import org.jerlang.type.Str;
import org.jerlang.type.Term;
import org.jerlang.type.Tuple;

/**
 * Reads terms in the Erlang external term format, as produced by
 * erlang:term_to_binary/1 and by other nodes over distribution.
 *
 * See:
 * http://erlang.org/doc/apps/erts/erl_ext_dist.html
 */
public class ExternalTermReader {

    private final DataInputStream inputStream;

    public ExternalTermReader(byte[] bytes) {
        this(new DataInputStream(new ByteArrayInputStream(bytes)));
    }

    public ExternalTermReader(DataInputStream inputStream) {
        this.inputStream = inputStream;
    }

    public Term read() throws Throwable {
        int versionOrTag = read1Byte();
        int tag = (versionOrTag == 131) ? read1Byte() : versionOrTag;
        return readTerm(tag);
    }

    private Term readTerm(int tag) throws Throwable {
        switch (tag) {
        case 70: // NEW_FLOAT_EXT
            return readNewFloat();
        case 77: // BIT_BINARY_EXT
            return readBitBinary();
        case 88: // NEW_PID_EXT
            return readNewPID();
        case 90: // NEWER_REFERENCE_EXT
            return readNewerReference();
        case 97: // SMALL_INTEGER_EXT
            return Integer.of(read1Byte());
        case 98: // INTEGER_EXT
            return Integer.of(read4Bytes());
        case 99: // FLOAT_EXT
            return readOldFloat();
        case 100: // ATOM_EXT
            return readAtom(read2Bytes());
        case 101: // REFERENCE_EXT
            return readReference();
        case 103: // PID_EXT
            return readPID();
        case 104: // SMALL_TUPLE_EXT
            return readTuple(read1Byte());
        case 105: // LARGE_TUPLE_EXT
            return readTuple(read4Bytes());
        case 106: // NIL_EXT
            return List.nil;
        case 107: // STRING_EXT
            return readString();
        case 108: // LIST_EXT
            return readList();
        case 109: // BINARY_EXT
            return readBinary();
        case 110: // SMALL_BIG_EXT
            return readBig(read1Byte());
        case 111: // LARGE_BIG_EXT
            return readBig(read4Bytes());
        case 114: // NEW_REFERENCE_EXT
            return readNewReference();
        case 116: // MAP_EXT
            return readMap();
        case 118: // ATOM_UTF8_EXT
            return readUtf8Atom(read2Bytes());
        case 119: // SMALL_ATOM_UTF8_EXT
            return readUtf8Atom(read1Byte());
        default:
            throw new Error("External term format tag " + tag + " is not supported");
        }
    }

    private Term readOldFloat() throws IOException {
        byte[] bytes = new byte[31];
        readBytes(bytes);
        String text = new String(bytes, StandardCharsets.ISO_8859_1);
        int end = text.indexOf('\0');
        if (end >= 0) {
            text = text.substring(0, end);
        }
        return Float.of(Double.parseDouble(text.trim()));
    }

    private Term readNewFloat() throws IOException {
        return new Float(Double.longBitsToDouble(read8Bytes()));
    }

    private Term readBitBinary() throws IOException {
        byte[] bytes = new byte[read4Bytes()];
        int usedBits = read1Byte();
        readBytes(bytes);
        if (usedBits == 8) {
            return new Binary(bytes);
        }
        return new BitString(bytes, 8 - usedBits);
    }

    private Term readMap() throws Throwable {
        int pairs = read4Bytes();
        Map map = new Map();
        while (pairs-- > 0) {
            map.set(read(), read());
        }
        return map;
    }

    private Atom readAtom(int length) throws IOException {
        byte[] bytes = new byte[length];
        readBytes(bytes);
        return Atom.of(bytes);
    }

    private Atom readUtf8Atom(int length) throws IOException {
        byte[] bytes = new byte[length];
        readBytes(bytes);
        return Atom.of(new String(bytes, StandardCharsets.UTF_8));
    }

    private Binary readBinary() throws IOException {
        byte[] bytes = new byte[read4Bytes()];
        readBytes(bytes);
        return new Binary(bytes);
    }

    private Term readList() throws Throwable {
        int length = read4Bytes();
        Term[] elements = new Term[length];
        for (int index = 0; index < length; index++) {
            elements[index] = read();
        }
        Term tail = read();
        if (tail instanceof List) {
            List list = (List) tail;
            for (int index = length - 1; index >= 0; index--) {
                list = new List(elements[index], list);
            }
            return list;
        }
        Term improper = tail;
        for (int index = length - 1; index >= 0; index--) {
            improper = new ImproperList(elements[index], improper);
        }
        return improper;
    }

    private Term readBig(int length) throws IOException {
        int sign = read1Byte();
        byte[] digits = new byte[length];
        readBytes(digits);
        // The digits are stored least significant byte first.
        byte[] bigEndian = new byte[length + 1];
        for (int index = 0; index < length; index++) {
            bigEndian[length - index] = digits[index];
        }
        BigInteger value = new BigInteger(bigEndian);
        if (sign == 1) {
            value = value.negate();
        }
        return new Integer(value);
    }

    private Tuple readTuple(int arity) throws Throwable {
        Term[] terms = new Term[arity];
        for (int index = 0; index < arity; index++) {
            terms[index] = read();
        }
        return Tuple.of(terms);
    }

    private Str readString() throws IOException {
        int length = read2Bytes();
        byte[] bytes = new byte[length];
        readBytes(bytes);
        return Str.of(new String(bytes, StandardCharsets.ISO_8859_1));
    }

    private PID readPID() throws Throwable {
        Atom node = readNode();
        int id = read4Bytes();
        int serial = read4Bytes();
        int creation = read1Byte();
        return new PID(node, id, serial, creation);
    }

    private PID readNewPID() throws Throwable {
        Atom node = readNode();
        int id = read4Bytes();
        int serial = read4Bytes();
        int creation = read4Bytes();
        return new PID(node, id, serial, creation);
    }

    private Reference readReference() throws Throwable {
        Atom node = readNode();
        long id = java.lang.Integer.toUnsignedLong(read4Bytes());
        int creation = read1Byte();
        return new Reference(node, id, creation);
    }

    private Reference readNewReference() throws Throwable {
        Atom node = readNode();
        int words = read4Bytes();
        int creation = read1Byte();
        return new Reference(node, readReferenceId(words), creation);
    }

    /**
     * NEWER_REFERENCE_EXT has a 16 bit length, then the node, then a
     * 32 bit creation number and the ID words.
     */
    private Reference readNewerReference() throws Throwable {
        int words = read2Bytes();
        Atom node = readNode();
        int creation = read4Bytes();
        return new Reference(node, readReferenceId(words), creation);
    }

    private BigInteger readReferenceId(int words) throws IOException {
        if (words < 1 || words > 256) {
            throw new Error("Can not read a reference with " + words + " words");
        }
        byte[] bytes = new byte[words * 4];
        readBytes(bytes);
        return new BigInteger(1, bytes);
    }

    private Atom readNode() throws Throwable {
        Term node = read();
        if (!(node instanceof Atom)) {
            throw new Error("Expected a node name, got " + node);
        }
        return (Atom) node;
    }

    private int read1Byte() throws IOException {
        return inputStream.readUnsignedByte();
    }

    private int read2Bytes() throws IOException {
        return inputStream.readUnsignedShort();
    }

    private int read4Bytes() throws IOException {
        return inputStream.readInt();
    }

    private long read8Bytes() throws IOException {
        return inputStream.readLong();
    }

    private int readBytes(byte[] bytes) throws IOException {
        int offset = 0;
        while (offset < bytes.length) {
            int read = inputStream.read(bytes, offset, bytes.length - offset);
            if (read < 0) {
                throw new IOException("Unexpected end of external term");
            }
            offset += read;
        }
        return offset;
    }

}
