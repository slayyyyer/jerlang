package org.jerlang.stdlib.beam_lib;

import java.io.ByteArrayOutputStream;
import java.math.BigInteger;
import java.util.HashMap;
import java.util.Map;

import org.jerlang.Opcode;
import org.jerlang.OpcodeTag;
import org.jerlang.erts.emulator.Instruction;
import org.jerlang.type.Atom;
import org.jerlang.type.Integer;
import org.jerlang.type.List;
import org.jerlang.type.Nil;
import org.jerlang.type.Term;
import org.jerlang.type.Tuple;

/**
 * = Writing a new Code chunk
 *
 * {@link CodeChunkWriter} re-encodes a payload that was read from a file and
 * keeps the compiler's header. A code generator has no such payload: it has
 * instructions and must choose the header. This class makes that choice in one
 * place, and encodes the operand forms the compiler emits.
 *
 * The operand vocabulary is deliberately small. Registers are {@code {x,N}},
 * {@code {y,N}} and {@code {f,N}}, atoms are {@code {atom,Atom}}, integers
 * are {@code {integer,Integer}}, lists are {@code {list,List}} and literal
 * table entries are {@code {literal,Index}}. Anything else is a compiler bug
 * and is refused rather than written as zero.
 */
public final class CodeChunkAssembler {

    private static final int HEADER_SIZE = 20;

    private CodeChunkAssembler() {
    }

    public static byte[] write(java.util.List<Instruction> instructions, AtomChunk atoms,
        int labels, int functions) {
        if (instructions.isEmpty()
            || instructions.get(instructions.size() - 1).opcode() != Opcode.int_code_end) {
            throw new IllegalArgumentException("code must end with int_code_end");
        }

        Map<Atom, java.lang.Integer> atomIndex = index(atoms);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        BeamBytes.write4(out, 16);
        BeamBytes.write4(out, 0);
        BeamBytes.write4(out, Opcode.max());
        BeamBytes.write4(out, labels);
        BeamBytes.write4(out, functions);
        for (Instruction instruction : instructions) {
            out.write(instruction.opcode().encode());
            for (int argument = 0; argument < instruction.arity(); argument++) {
                operand(instruction.arg(argument), atomIndex).writeTo(out);
            }
        }
        return out.toByteArray();
    }

    private static Map<Atom, java.lang.Integer> index(AtomChunk atoms) {
        Map<Atom, java.lang.Integer> index = new HashMap<>();
        Atom[] table = atoms.atoms();
        for (int position = 0; position < table.length; position++) {
            index.putIfAbsent(table[position], position + 1);
        }
        return index;
    }

    private static BeamOperand operand(Term term, Map<Atom, java.lang.Integer> atoms) {
        if (term instanceof Integer) {
            Integer integer = (Integer) term;
            return integer(integer.toBigInteger().signum() < 0 ? OpcodeTag.i : OpcodeTag.u,
                integer.toBigInteger(), term);
        }
        if (term instanceof Atom) {
            return atom((Atom) term, atoms);
        }
        if (term instanceof Nil || term == List.nil) {
            return integer(OpcodeTag.a, BigInteger.ZERO, List.nil);
        }
        if (term instanceof List) {
            return list((List) term, atoms);
        }
        if (!(term instanceof Tuple)) {
            throw new IllegalArgumentException("unsupported instruction operand: " + term);
        }

        Tuple tuple = term.toTuple();
        if (tuple.arity() != 2) {
            throw new IllegalArgumentException("unsupported instruction operand: " + term);
        }
        String kind = tuple.element(1).toAtom().toString();
        Term value = tuple.element(2);
        switch (kind) {
        case "x":
            return integer(OpcodeTag.x, value.toInteger().toBigInteger(), term);
        case "y":
            return integer(OpcodeTag.y, value.toInteger().toBigInteger(), term);
        case "f":
            return integer(OpcodeTag.f, value.toInteger().toBigInteger(), term);
        case "atom":
            return atom(value.toAtom(), atoms);
        case "integer":
            return integer(OpcodeTag.i, value.toInteger().toBigInteger(), term);
        case "list":
            return list(value.toList(), atoms);
        case "literal":
            return BeamOperand.literal(operand(value, atoms), List.nil);
        default:
            throw new IllegalArgumentException("unsupported instruction operand: " + term);
        }
    }

    private static BeamOperand atom(Atom atom, Map<Atom, java.lang.Integer> atoms) {
        java.lang.Integer index = atoms.get(atom);
        if (index == null) {
            throw new IllegalArgumentException("atom not in the atom table: " + atom);
        }
        return integer(OpcodeTag.a, BigInteger.valueOf(index), atom);
    }

    private static BeamOperand list(List list, Map<Atom, java.lang.Integer> atoms) {
        int count = list.length();
        BeamOperand[] elements = new BeamOperand[count];
        List cursor = list;
        for (int position = 0; position < count; position++) {
            elements[position] = operand(cursor.head(), atoms);
            cursor = cursor.tail();
        }
        return BeamOperand.list(integer(OpcodeTag.u, BigInteger.valueOf(count), list),
            elements, list);
    }

    private static BeamOperand integer(OpcodeTag tag, BigInteger value, Term term) {
        int form;
        int width = 0;
        if (value.signum() >= 0 && value.compareTo(BigInteger.valueOf(16)) < 0) {
            form = BeamOperand.FOUR_BIT;
        } else if (value.signum() >= 0 && value.compareTo(BigInteger.valueOf(2048)) < 0) {
            form = BeamOperand.ELEVEN_BIT;
        } else {
            form = BeamOperand.BIGNUM;
            int bits = value.bitLength() + (value.signum() < 0 ? 1 : 0);
            width = Math.max(2, (bits + 7) / 8);
        }
        return BeamOperand.integer(tag.ordinal(), form, value, width, term);
    }

}
