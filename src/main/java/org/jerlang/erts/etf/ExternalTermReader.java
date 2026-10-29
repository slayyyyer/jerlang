package org.jerlang.erts.etf;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.util.zip.InflaterInputStream;

import org.jerlang.FunctionSignature;
import org.jerlang.Module;
import org.jerlang.ModuleRegistry;
import org.jerlang.erts.erlang.Error;
import org.jerlang.stdlib.beam_lib.LambdaInfo;
import org.jerlang.type.Atom;
import org.jerlang.type.Binary;
import org.jerlang.type.BitString;
import org.jerlang.type.Float;
import org.jerlang.type.Fun;
import org.jerlang.type.ImproperList;
import org.jerlang.type.Integer;
import org.jerlang.type.List;
import org.jerlang.type.Map;
import org.jerlang.type.PID;
import org.jerlang.type.PortID;
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
        case 80: // COMPRESSED
            return readCompressed();
        case 112: // NEW_FUN_EXT
            return readNewFun();
        case 113: // EXPORT_EXT
            return readExportFun();
        case 117: // FUN_EXT
            return readFun();
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
        case 102: // PORT_EXT, one byte creation
            return readPort();
        case 120: // V4_PORT_EXT, four byte creation
            return readV4Port();
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

    /**
     * A compressed term carries its uncompressed size and then a zlib
     * stream. term_to_binary/2 can compress the term that follows the
     * distribution header, so the rest of the current stream belongs to it.
     */
    private Term readCompressed() throws Throwable {
        int uncompressedSize = read4Bytes();
        byte[] compressed = inputStream.readAllBytes();
        ByteArrayOutputStream out = new ByteArrayOutputStream(Math.max(0, uncompressedSize));
        try (InflaterInputStream inflater = new InflaterInputStream(
            new ByteArrayInputStream(compressed))) {
            byte[] buffer = new byte[4096];
            int read;
            while ((read = inflater.read(buffer)) >= 0) {
                out.write(buffer, 0, read);
            }
        }
        return new ExternalTermReader(out.toByteArray()).read();
    }

    private Term readNewFun() throws Throwable {
        read4Bytes(); // Size, including the size field itself.
        int arity = read1Byte();
        byte[] uniq = new byte[16];
        readBytes(uniq);
        int index = read4Bytes();
        int numFree = read4Bytes();
        Atom module = readNode();
        // OldIndex and OldUniq are integer terms, not raw 32 bit fields.
        readIntegerTerm();
        readIntegerTerm();
        PID owner = readPIDTerm();
        Term[] free = readFreeVariables(numFree);
        return internalFun(module, index, arity, owner, free);
    }

    private Term readFun() throws Throwable {
        int numFree = read4Bytes();
        PID owner = readPIDTerm();
        Atom module = readNode();
        int index = read4Bytes();
        read4Bytes(); // Uniq
        Term[] free = readFreeVariables(numFree);
        return internalFun(module, index, -1, owner, free);
    }

    private Term readExportFun() throws Throwable {
        Atom module = readNode();
        Term function = read();
        if (!(function instanceof Atom)) {
            throw new Error("EXPORT_EXT function is not an atom: " + function);
        }
        Term arity = read();
        if (!(arity instanceof Integer)) {
            throw new Error("EXPORT_EXT arity is not an integer: " + arity);
        }
        return new Fun(new FunctionSignature(module, (Atom) function, (Integer) arity), null);
    }

    private void readIntegerTerm() throws Throwable {
        Term term = read();
        if (!(term instanceof Integer)) {
            throw new Error("Expected an integer, got " + term);
        }
    }

    private PID readPIDTerm() throws Throwable {
        Term term = read();
        if (!(term instanceof PID)) {
            throw new Error("Expected a fun owner pid, got " + term);
        }
        return (PID) term;
    }

    private Term[] readFreeVariables(int numFree) throws Throwable {
        if (numFree < 0 || numFree > 1_000_000) {
            throw new Error("Invalid number of fun free variables: " + numFree);
        }
        Term[] free = new Term[numFree];
        for (int index = 0; index < numFree; index++) {
            free[index] = read();
        }
        return free;
    }

    private Fun internalFun(Atom moduleName, int index, int arity, PID owner, Term[] free)
        throws Throwable {
        Module module = ModuleRegistry.get(moduleName);
        if (module == null || module.beamData() == null
            || module.beamData().functionTableChunk() == null) {
            throw new Error("Fun module is not loaded: " + moduleName);
        }
        LambdaInfo lambda = module.beamData().functionTableChunk().lambdas().get(index);
        if (lambda == null) {
            throw new Error("No lambda " + index + " in module " + moduleName);
        }
        int expectedArity = lambda.toFunctionSignature(moduleName).fun_arity().toInt()
            - lambda.numFree();
        if (arity >= 0 && expectedArity != arity) {
            throw new Error("Fun arity does not match lambda " + index + " in " + moduleName);
        }
        if (free.length != lambda.numFree()) {
            throw new Error("Fun free-variable count does not match lambda " + index
                + " in " + moduleName);
        }
        return new Fun(module, lambda, owner, free);
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

    /**
     * The old port tag has a four byte id and a one byte creation.
     */
    private PortID readPort() throws Throwable {
        Atom node = readNode();
        int id = read4Bytes();
        int creation = read1Byte();
        return new PortID(node, id, creation);
    }

    /**
     * OTP 26 and later send ports with a 64 bit id and a four byte
     * creation, the same creation form as NEW_PID_EXT.
     */
    private PortID readV4Port() throws Throwable {
        Atom node = readNode();
        int highId = read4Bytes();
        int id = read4Bytes();
        int creation = read4Bytes();
        if (highId != 0) {
            throw new Error("Unsupported port identifier: "
                + java.lang.Integer.toUnsignedString(highId) + ":"
                + java.lang.Integer.toUnsignedString(id));
        }
        return new PortID(node, id, creation);
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
