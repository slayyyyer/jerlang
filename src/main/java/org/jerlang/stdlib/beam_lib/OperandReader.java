package org.jerlang.stdlib.beam_lib;

import java.io.DataInputStream;
import java.io.IOException;
import java.math.BigInteger;

import org.jerlang.OpcodeTag;
import org.jerlang.stdlib.Lists;
import org.jerlang.type.Atom;
import org.jerlang.type.Integer;
import org.jerlang.type.List;
import org.jerlang.type.Term;
import org.jerlang.type.Tuple;

/**
 * Reads one instruction operand into a {@link BeamOperand}.
 *
 * This is the decode side of the operand codec and the only implementation of
 * it: {@link AbstractReader#decodeArg} delegates here and returns the term, so
 * the interpreter and the assembler agree about what an operand is by
 * construction rather than by two parallel decoders that can drift.
 */
public class OperandReader extends AbstractReader {

    public OperandReader(DataInputStream inputStream) {
        super(inputStream);
    }

    public BeamOperand read(AtomChunk atomChunk, LiteralTableChunk literalTableChunk)
        throws IOException {
        int b = read1Byte();
        OpcodeTag tag = OpcodeTag.decode(b);
        switch (tag) {
        case a:
            BeamOperand index = readInteger(b, OpcodeTag.i);
            int atomIndex = index.term().toInteger().toInt();
            if (atomIndex == 0) {
                return index.withTerm(List.nil);
            }
            return index.withTerm(atomChunk.atoms()[atomIndex - 1]);
        case i:
        case u:
            return readInteger(b, tag);
        case z:
            if ((b & 0x08) != 0) {
                throw new Error("invalid extended tag: " + b);
            }
            switch (b >> 4) {
            case 0:
                throw new Error("decode float not implemented yet");
            case 1:
                return readList(atomChunk, literalTableChunk);
            case 2:
                // Float register. The reader has always taken this as one raw
                // byte rather than a compact integer, so the byte is kept as
                // read: a writer that "improved" it would move every byte that
                // follows.
                int register = read1Byte();
                return BeamOperand.floatRegister(register,
                    Tuple.of(Atom.of("fr"), Integer.of(register)));
            case 3:
                return readAllocation(atomChunk, literalTableChunk);
            case 4:
                BeamOperand literalIndex = read(atomChunk, literalTableChunk);
                Term literal = literalTableChunk.literals()
                    .get(literalIndex.term().toInteger().toInt());
                return BeamOperand.literal(literalIndex, literal);
            case 5:
                BeamOperand typedRegister = read(atomChunk, literalTableChunk);
                BeamOperand type = read(atomChunk, literalTableChunk);
                return BeamOperand.typedRegister(typedRegister, type, typedRegister.term());
            default:
                throw new Error("unsupported z value: " + (b >> 4));
            }
        default:
            BeamOperand number = readInteger(b, OpcodeTag.i);
            return number.withTerm(Tuple.of(tag.toAtom(), number.term()));
        }
    }

    private BeamOperand readList(AtomChunk atomChunk, LiteralTableChunk literalTableChunk)
        throws IOException {
        BeamOperand count = read(atomChunk, literalTableChunk);
        int elements = count.term().toInteger().toInt();
        BeamOperand[] items = new BeamOperand[elements];
        List list = List.nil;
        for (int index = 0; index < elements; index++) {
            items[index] = read(atomChunk, literalTableChunk);
            list = new List(items[index].term(), list);
        }
        return BeamOperand.list(count, items, Lists.reverse(list));
    }

    private BeamOperand readAllocation(AtomChunk atomChunk, LiteralTableChunk literalTableChunk)
        throws IOException {
        BeamOperand count = read(atomChunk, literalTableChunk);
        int entries = count.term().toInteger().toInt();
        BeamOperand[] items = new BeamOperand[entries * 2];
        List allocation = List.nil;
        for (int index = 0; index < entries; index++) {
            BeamOperand type = read(atomChunk, literalTableChunk);
            BeamOperand amount = read(atomChunk, literalTableChunk);
            items[index * 2] = type;
            items[index * 2 + 1] = amount;
            int typ = type.term().toInteger().toInt();
            int val = amount.term().toInteger().toInt();
            switch (typ) {
            case 0:
                allocation = new List(Tuple.of(Atom.of("words"), Integer.of(val)), allocation);
                break;
            case 1:
                allocation = new List(Tuple.of(Atom.of("floats"), Integer.of(val)), allocation);
                break;
            case 2:
                allocation = new List(Tuple.of(Atom.of("literal"), Integer.of(val)), allocation);
                break;
            default:
                break;
            }
        }
        return BeamOperand.allocation(count, items, allocation);
    }

    /**
     * Reads the compact integer whose first byte has already been read. The
     * form is kept because the same number has three encodings and only the one
     * that was used reproduces the file.
     */
    private BeamOperand readInteger(int b, OpcodeTag opcodeTag) throws IOException {
        if ((b & 0x08) == 0) {
            return BeamOperand.integer(b & 0x07, BeamOperand.FOUR_BIT,
                BigInteger.valueOf(b >> 4), 0, Integer.of(b >> 4));
        }
        if ((b & 0x10) == 0) {
            int value = ((b & 0xE0) << 3) | read1Byte();
            return BeamOperand.integer(b & 0x07, BeamOperand.ELEVEN_BIT,
                BigInteger.valueOf(value), 0, Integer.of(value));
        }
        int width = readLength(b);
        // The width is read out of the file, so it is checked against what the
        // file can still supply. Without this a mutated instruction can make
        // the decoder allocate a huge array and spend minutes shifting a
        // BigInteger, which is a hang rather than a refusal.
        if (width < 0 || width > inputStream().available()) {
            throw new IllegalArgumentException("bignum length " + width
                + " exceeds the " + inputStream().available() + " bytes left in the file");
        }
        byte[] bytes = new byte[width];
        readBytes(bytes);
        BigInteger value = BigInteger.ZERO;
        for (byte x : bytes) {
            value = value.shiftLeft(8).or(BigInteger.valueOf(x & 0xFF));
        }
        if ((bytes[0] & 0x80) != 0 && opcodeTag == OpcodeTag.i) {
            value = value.subtract(BigInteger.ONE.shiftLeft(width * 8));
        }
        return BeamOperand.integer(b & 0x07, BeamOperand.BIGNUM, value, width,
            new Integer(value));
    }

    private int readLength(int b) throws IOException {
        int len = b >>> 5;
        if (len == 7) {
            Term term = read(null, null).term();
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
