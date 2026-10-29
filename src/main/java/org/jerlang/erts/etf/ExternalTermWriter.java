package org.jerlang.erts.etf;

import java.io.ByteArrayOutputStream;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;

import org.jerlang.FunctionSignature;
import org.jerlang.Module;
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
import org.jerlang.type.Nil;
import org.jerlang.type.PID;
import org.jerlang.type.Reference;
import org.jerlang.type.Str;
import org.jerlang.type.Term;
import org.jerlang.type.Tuple;

/**
 * Writes terms in the Erlang external term format, i.e. the format that
 * erlang:term_to_binary/1 returns and that nodes exchange over
 * distribution.
 *
 * See:
 * http://erlang.org/doc/apps/erts/erl_ext_dist.html
 */
public class ExternalTermWriter {

    private static final int VERSION = 131;

    // External term format tags, see erts/emulator/beam/external.h
    private static final int NEW_FLOAT_EXT = 70;
    private static final int BIT_BINARY_EXT = 77;
    private static final int NEW_PID_EXT = 88;
    private static final int NEWER_REFERENCE_EXT = 90;
    private static final int SMALL_INTEGER_EXT = 97;
    private static final int INTEGER_EXT = 98;
    private static final int ATOM_EXT = 100;
    private static final int SMALL_TUPLE_EXT = 104;
    private static final int LARGE_TUPLE_EXT = 105;
    private static final int NIL_EXT = 106;
    private static final int STRING_EXT = 107;
    private static final int LIST_EXT = 108;
    private static final int BINARY_EXT = 109;
    private static final int SMALL_BIG_EXT = 110;
    private static final int LARGE_BIG_EXT = 111;
    private static final int MAP_EXT = 116;
    private static final int NEW_FUN_EXT = 112;
    private static final int EXPORT_EXT = 113;
    private static final int ATOM_UTF8_EXT = 118;
    private static final int SMALL_ATOM_UTF8_EXT = 119;

    private final ByteArrayOutputStream buffer = new ByteArrayOutputStream();

    /**
     * Encode a term, including the version byte.
     */
    public static Binary encode(Term term) {
        ExternalTermWriter writer = new ExternalTermWriter();
        writer.write(term);
        return new Binary(writer.toByteArray());
    }

    public void write(Term term) {
        writeByte(VERSION);
        writeTerm(term);
    }

    /**
     * Encode a term without the version byte, as it appears inside a
     * distribution message after the version byte has been written.
     */
    public void writeTerm(Term term) {
        if (term instanceof Atom) {
            writeAtom((Atom) term);
        } else if (term instanceof Integer) {
            writeInteger((Integer) term);
        } else if (term instanceof Float) {
            writeFloat((Float) term);
        } else if (term instanceof BitString) {
            writeBitString((BitString) term);
        } else if (term instanceof Str) {
            writeStr((Str) term);
        } else if (term instanceof Nil) {
            writeByte(NIL_EXT);
        } else if (term instanceof List) {
            writeList((List) term);
        } else if (term instanceof ImproperList) {
            writeImproperList((ImproperList) term);
        } else if (term instanceof Tuple) {
            writeTuple((Tuple) term);
        } else if (term instanceof Map) {
            writeMap((Map) term);
        } else if (term instanceof PID) {
            writePID((PID) term);
        } else if (term instanceof Reference) {
            writeReference((Reference) term);
        } else if (term instanceof Fun) {
            writeFun((Fun) term);
        } else {
            throw new Error("term_to_binary/1 can not encode " + term);
        }
    }

    public byte[] toByteArray() {
        return buffer.toByteArray();
    }

    private void writeAtom(Atom atom) {
        byte[] name = atom.toString().getBytes(StandardCharsets.UTF_8);
        if (name.length <= 255) {
            writeByte(SMALL_ATOM_UTF8_EXT);
            writeByte(name.length);
        } else {
            writeByte(ATOM_UTF8_EXT);
            writeShort(name.length);
        }
        buffer.write(name, 0, name.length);
    }

    private void writeInteger(Integer integer) {
        BigInteger value = integer.toBigInteger();
        if (value.signum() >= 0 && value.bitLength() <= 8) {
            writeByte(SMALL_INTEGER_EXT);
            writeByte(value.intValue());
        } else if (value.compareTo(BigInteger.valueOf(java.lang.Integer.MIN_VALUE)) >= 0
            && value.compareTo(BigInteger.valueOf(java.lang.Integer.MAX_VALUE)) <= 0) {
            writeByte(INTEGER_EXT);
            writeInt(value.intValue());
        } else {
            writeBigInteger(value);
        }
    }

    /**
     * Bignums are stored with a sign byte and the digits least
     * significant byte first.
     */
    private void writeBigInteger(BigInteger value) {
        BigInteger magnitude = value.abs();
        byte[] bigEndian = magnitude.toByteArray();
        int start = (bigEndian.length > 1 && bigEndian[0] == 0) ? 1 : 0;
        int length = bigEndian.length - start;
        if (length <= 255) {
            writeByte(SMALL_BIG_EXT);
            writeByte(length);
        } else {
            writeByte(LARGE_BIG_EXT);
            writeInt(length);
        }
        writeByte(value.signum() < 0 ? 1 : 0);
        for (int index = bigEndian.length - 1; index >= start; index--) {
            writeByte(bigEndian[index]);
        }
    }

    private void writeFloat(Float value) {
        writeByte(NEW_FLOAT_EXT);
        long bits = Double.doubleToLongBits(value.toBigDecimal().doubleValue());
        for (int shift = 56; shift >= 0; shift -= 8) {
            writeByte((int) ((bits >>> shift) & 0xFF));
        }
    }

    private void writeBitString(BitString bitString) {
        int[] bytes = bitString.bytes();
        int unusedBits = bitString.unusedBits();
        if (unusedBits == 0) {
            writeByte(BINARY_EXT);
            writeInt(bytes.length);
        } else {
            writeByte(BIT_BINARY_EXT);
            writeInt(bytes.length);
            writeByte(8 - unusedBits);
        }
        for (int index = 0; index < bytes.length; index++) {
            writeByte(bytes[index]);
        }
    }

    /**
     * A string is a list of characters, but it is encoded in the more
     * compact STRING_EXT format when all characters fit in one byte.
     */
    private void writeStr(Str str) {
        String string = str.string();
        if (string.isEmpty()) {
            writeByte(NIL_EXT);
            return;
        }
        if (string.length() <= 0xFFFF && allLatin1(string)) {
            writeByte(STRING_EXT);
            writeShort(string.length());
            for (int index = 0; index < string.length(); index++) {
                writeByte(string.charAt(index));
            }
        } else {
            writeList(str);
        }
    }

    private boolean allLatin1(String string) {
        for (int index = 0; index < string.length(); index++) {
            if (string.charAt(index) > 0xFF) {
                return false;
            }
        }
        return true;
    }

    private void writeList(List list) {
        int length = 0;
        for (List cursor = list; !(cursor instanceof Nil); cursor = cursor.tail()) {
            length++;
        }
        writeByte(LIST_EXT);
        writeInt(length);
        for (List cursor = list; !(cursor instanceof Nil); cursor = cursor.tail()) {
            writeTerm(cursor.head());
        }
        writeByte(NIL_EXT);
    }

    private void writeImproperList(ImproperList list) {
        int length = 1;
        Term tail = list.tail();
        while (tail instanceof ImproperList) {
            length++;
            tail = ((ImproperList) tail).tail();
        }
        writeByte(LIST_EXT);
        writeInt(length);
        writeTerm(list.head());
        for (Term cursor = list.tail(); cursor instanceof ImproperList;
            cursor = ((ImproperList) cursor).tail()) {
            writeTerm(((ImproperList) cursor).head());
        }
        writeTerm(tail);
    }

    private void writeTuple(Tuple tuple) {
        int arity = tuple.arity();
        if (arity <= 255) {
            writeByte(SMALL_TUPLE_EXT);
            writeByte(arity);
        } else {
            writeByte(LARGE_TUPLE_EXT);
            writeInt(arity);
        }
        for (int index = 1; index <= arity; index++) {
            writeTerm(tuple.element(index));
        }
    }

    private void writeMap(Map map) {
        List keys = map.keys();
        writeByte(MAP_EXT);
        writeInt(keys.length());
        while (!(keys instanceof Nil)) {
            Term key = keys.head();
            writeTerm(key);
            writeTerm(map.get(key));
            keys = keys.tail();
        }
    }

    /**
     * OTP 26 and later encode PIDs with a 4 byte creation number, which
     * is also what a node uses when it talks to another node.
     */
    private void writePID(PID pid) {
        writeByte(NEW_PID_EXT);
        writeTerm(pid.node());
        writeInt(pid.processId());
        writeInt(pid.serial());
        writeInt(pid.creation());
    }

    /**
     * OTP 26 and later use NEWER_REFERENCE_EXT (tag 90) for all
     * references. Its layout is a 16 bit length, the node, a 32 bit
     * creation number, then the ID words in big-endian order.
     */
    private void writeReference(Reference reference) {
        byte[] id = reference.id().toByteArray();
        int start = (id.length > 1 && id[0] == 0) ? 1 : 0;
        int length = id.length - start;
        int words = (length + 3) / 4;
        // OTP 26 and later allow up to five 32 bit words in a
        // NEWER_REFERENCE_EXT (the V4_NC container format).
        if (words > 5) {
            throw new Error("term_to_binary/1 can not encode a reference with " + words + " words");
        }
        writeByte(NEWER_REFERENCE_EXT);
        writeShort(words);
        writeTerm(reference.node());
        writeInt(reference.creation());
        int padding = (words * 4) - length;
        for (int index = 0; index < padding; index++) {
            writeByte(0);
        }
        for (int index = start; index < id.length; index++) {
            writeByte(id[index]);
        }
    }

    /**
     * Internal funs use NEW_FUN_EXT, which carries the lambda table index,
     * the module digest, the creator pid and the free variables. External
     * funs (fun M:F/A) use EXPORT_EXT.
     */
    private void writeFun(Fun fun) {
        if (fun.isInternal()) {
            writeNewFun(fun);
        } else {
            writeExportFun(fun);
        }
    }

    private void writeNewFun(Fun fun) {
        Module module = fun.module();
        LambdaInfo lambda = fun.lambdaInfo();
        if (module == null || lambda == null) {
            throw new Error("term_to_binary/1 can not encode an incomplete fun");
        }
        Term[] free = fun.savedRegisters();
        ExternalTermWriter body = new ExternalTermWriter();
        body.writeByte(fun.arity());
        body.writeBytes(module.md5());
        body.writeInt(lambda.index());
        body.writeInt(free.length);
        body.writeTerm(module.name());
        body.writeInt(lambda.oldIndex());
        body.writeInt(lambda.oldUnique());
        body.writeTerm(fun.owner() == null ? new PID(0) : fun.owner());
        for (Term term : free) {
            body.writeTerm(term);
        }
        byte[] bytes = body.toByteArray();
        writeByte(NEW_FUN_EXT);
        // Size includes the four-byte size field itself.
        writeInt(4 + bytes.length);
        buffer.write(bytes, 0, bytes.length);
    }

    private void writeExportFun(Fun fun) {
        FunctionSignature signature = fun.signature();
        writeByte(EXPORT_EXT);
        writeTerm(signature.module());
        writeTerm(signature.function());
        writeTerm(signature.fun_arity());
    }

    private void writeByte(int value) {
        buffer.write(value & 0xFF);
    }

    private void writeBytes(byte[] bytes) {
        buffer.write(bytes, 0, bytes.length);
    }

    private void writeShort(int value) {
        writeByte(value >>> 8);
        writeByte(value);
    }

    private void writeInt(int value) {
        writeByte(value >>> 24);
        writeByte(value >>> 16);
        writeByte(value >>> 8);
        writeByte(value);
    }

}
