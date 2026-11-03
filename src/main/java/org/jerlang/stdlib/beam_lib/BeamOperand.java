package org.jerlang.stdlib.beam_lib;

import java.io.ByteArrayOutputStream;
import java.math.BigInteger;

import org.jerlang.type.Term;

/**
 * = One instruction operand and the bytes it came from
 *
 * The interpreter only needs the value of an operand, so the reader used to
 * return a {@code Term} and throw the encoding away. A writer can not do that:
 * the compact integer encoding has three forms for the same number, the
 * extended {@code z} tags carry a sub-opcode, and a literal operand is an index
 * into the literal table rather than the literal itself. Two modules with the
 * same instructions but different operand widths are different modules to
 * {@code beam_lib:md5/1}, which hashes the code chunk as bytes.
 *
 * An instance keeps both: {@link #term()} is what the interpreter sees, and
 * {@link #writeTo(ByteArrayOutputStream)} reproduces the operand exactly as it
 * was read.
 */
public final class BeamOperand {

    public static final int FOUR_BIT = 0;
    public static final int ELEVEN_BIT = 1;
    public static final int BIGNUM = 2;

    private static final int Z = 7;

    private enum Kind {
        INTEGER,
        LIST,
        FLOAT_REGISTER,
        ALLOCATION,
        LITERAL,
        TYPED_REGISTER
    }

    private final Kind kind;
    private final int tag;
    private final Term term;
    private final int form;
    private final BigInteger value;
    private final int width;
    private final int rawByte;
    private final BeamOperand[] children;

    private BeamOperand(Kind kind, int tag, Term term, int form, BigInteger value, int width,
        int rawByte, BeamOperand[] children) {
        this.kind = kind;
        this.tag = tag;
        this.term = term;
        this.form = form;
        this.value = value;
        this.width = width;
        this.rawByte = rawByte;
        this.children = children;
    }

    static BeamOperand integer(int tag, int form, BigInteger value, int width, Term term) {
        return new BeamOperand(Kind.INTEGER, tag, term, form, value, width, 0, null);
    }

    static BeamOperand list(BeamOperand count, BeamOperand[] elements, Term term) {
        BeamOperand[] children = new BeamOperand[elements.length + 1];
        children[0] = count;
        System.arraycopy(elements, 0, children, 1, elements.length);
        return new BeamOperand(Kind.LIST, Z, term, 0, null, 0, 0, children);
    }

    static BeamOperand floatRegister(int rawByte, Term term) {
        return new BeamOperand(Kind.FLOAT_REGISTER, Z, term, 0, null, 0, rawByte, null);
    }

    static BeamOperand allocation(BeamOperand count, BeamOperand[] entries, Term term) {
        BeamOperand[] children = new BeamOperand[entries.length + 1];
        children[0] = count;
        System.arraycopy(entries, 0, children, 1, entries.length);
        return new BeamOperand(Kind.ALLOCATION, Z, term, 0, null, 0, 0, children);
    }

    static BeamOperand literal(BeamOperand index, Term term) {
        return new BeamOperand(Kind.LITERAL, Z, term, 0, null, 0, 0,
            new BeamOperand[] { index });
    }

    static BeamOperand typedRegister(BeamOperand register, BeamOperand type, Term term) {
        return new BeamOperand(Kind.TYPED_REGISTER, Z, term, 0, null, 0, 0,
            new BeamOperand[] { register, type });
    }

    BeamOperand withTerm(Term replacement) {
        return new BeamOperand(kind, tag, replacement, form, value, width, rawByte, children);
    }

    public Term term() {
        return term;
    }

    public int tag() {
        return tag;
    }

    public int form() {
        return form;
    }

    /**
     * The sub-opcode of an extended operand, or -1 for a plain integer.
     */
    public int extendedTag() {
        switch (kind) {
        case LIST:
            return 1;
        case FLOAT_REGISTER:
            return 2;
        case ALLOCATION:
            return 3;
        case LITERAL:
            return 4;
        case TYPED_REGISTER:
            return 5;
        default:
            return -1;
        }
    }

    public void writeTo(ByteArrayOutputStream out) {
        switch (kind) {
        case INTEGER:
            BeamBytes.writeInteger(out, form, value, width, tag, tag == 0);
            return;
        case LIST:
            out.write(0x10 | Z);
            writeChildren(out);
            return;
        case FLOAT_REGISTER:
            out.write(0x20 | Z);
            out.write(rawByte);
            return;
        case ALLOCATION:
            out.write(0x30 | Z);
            writeChildren(out);
            return;
        case LITERAL:
            out.write(0x40 | Z);
            writeChildren(out);
            return;
        case TYPED_REGISTER:
            out.write(0x50 | Z);
            writeChildren(out);
            return;
        default:
            throw new IllegalStateException("unknown operand kind: " + kind);
        }
    }

    public byte[] toBytes() {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        writeTo(out);
        return out.toByteArray();
    }

    private void writeChildren(ByteArrayOutputStream out) {
        for (BeamOperand child : children) {
            child.writeTo(out);
        }
    }

}
