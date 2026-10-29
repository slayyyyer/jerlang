package org.jerlang.erts.emulator.op;

import java.math.BigInteger;

import org.jerlang.Module;
import org.jerlang.Process;
import org.jerlang.erts.emulator.Instruction;
import org.jerlang.erts.erlang.Error;
import org.jerlang.type.Atom;
import org.jerlang.type.Float;
import org.jerlang.type.Integer;
import org.jerlang.type.List;
import org.jerlang.type.Term;
import org.jerlang.type.Tuple;
import org.jerlang.type.stack.BinMatchState;

/**
 * The OTP 26 and later binary matching instruction. The compiler folds the
 * individual get, skip and literal compare operations of a match into one
 * command list, which the BEAM stores as a flat list:
 *
 *   [ensure_at_least, 8, 8,
 *    integer, 2, [], 8, 1, {x,0},
 *    get_tail, 2, 8, {x,1}]
 *
 * Every command has a fixed arity, so the list is read in order. A command
 * that does not fit jumps to the fail label of the instruction.
 *
 * Arguments:
 * 1. Fail label
 * 2. Match context
 * 3. Command list
 *
 * Example:
 * {bs_match,{f,3},{x,1},{commands,[...]}}
 */
public class BsMatch {

    private static final Atom ENSURE_AT_LEAST = Atom.of("ensure_at_least");
    private static final Atom ENSURE_EXACTLY = Atom.of("ensure_exactly");
    private static final Atom GET_TAIL = Atom.of("get_tail");
    private static final Atom SKIP = Atom.of("skip");
    private static final Atom MATCH_LITERAL = Atom.of("=:=");
    private static final Atom INTEGER = Atom.of("integer");
    private static final Atom BINARY = Atom.of("binary");
    private static final Atom FLOAT = Atom.of("float");
    private static final Atom ALL = Atom.of("all");

    public static Term execute(Process proc, Module m, Instruction i, List params) {
        BinMatchState context = i.arg(1).toArg(proc).toBinMatchState();
        List commands = i.arg(2).toList();

        while (commands.length() > 0) {
            Atom name = commands.head().toAtom();
            commands = commands.tail();

            if (ENSURE_AT_LEAST.equals(name)) {
                int bits = integer(commands.head(), proc);
                commands = commands.tail();
                int unit = integer(commands.head(), proc);
                commands = commands.tail();
                if (!context.ensureAtLeast(bits, unit)) {
                    return i.arg(0);
                }
            } else if (ENSURE_EXACTLY.equals(name)) {
                int bits = integer(commands.head(), proc);
                commands = commands.tail();
                if (!context.ensureExactly(bits)) {
                    return i.arg(0);
                }
            } else if (SKIP.equals(name)) {
                int bits = integer(commands.head(), proc);
                commands = commands.tail();
                if (!context.skip(bits)) {
                    return i.arg(0);
                }
            } else if (GET_TAIL.equals(name)) {
                commands = commands.tail(); // Live registers
                int unit = integer(commands.head(), proc);
                commands = commands.tail();
                Term destination = commands.head();
                commands = commands.tail();
                Term tail = context.readTail(unit);
                if (tail == null) {
                    return i.arg(0);
                }
                BsStartMatch3.write(proc, destination, tail);
            } else if (MATCH_LITERAL.equals(name)) {
                commands = commands.tail(); // Live registers
                int bits = integer(commands.head(), proc);
                commands = commands.tail();
                Term value = commands.head().toArg(proc);
                commands = commands.tail();
                if (!context.matchLiteral(bits, value.toInteger().toBigInteger())) {
                    return i.arg(0);
                }
            } else if (INTEGER.equals(name) || BINARY.equals(name) || FLOAT.equals(name)) {
                commands = commands.tail(); // Live registers
                Term flags = commands.head();
                commands = commands.tail();
                Term size = commands.head().toArg(proc);
                commands = commands.tail();
                int unit = integer(commands.head(), proc);
                commands = commands.tail();
                Term destination = commands.head();
                commands = commands.tail();

                if (BINARY.equals(name) && ALL.equals(size)) {
                    Term value = context.readTail(unit);
                    if (value == null) {
                        return i.arg(0);
                    }
                    BsStartMatch3.write(proc, destination, value);
                    continue;
                }

                int bits = size.toInteger().toInt() * unit;

                if (INTEGER.equals(name)) {
                    boolean signed = hasFlag(flags, "signed");
                    boolean little = hasFlag(flags, "little") || hasFlag(flags, "native");
                    Integer value = context.readInteger(bits, signed, little);
                    if (value == null) {
                        return i.arg(0);
                    }
                    BsStartMatch3.write(proc, destination, value);
                } else if (FLOAT.equals(name)) {
                    boolean little = hasFlag(flags, "little") || hasFlag(flags, "native");
                    Float value = context.readFloat(bits, little);
                    if (value == null) {
                        return i.arg(0);
                    }
                    BsStartMatch3.write(proc, destination, value);
                } else {
                    Term value = context.readBinary(bits);
                    if (value == null) {
                        return i.arg(0);
                    }
                    BsStartMatch3.write(proc, destination, value);
                }
            } else {
                throw new Error("Unsupported bs_match command: " + name);
            }
        }

        return null;
    }

    private static int integer(Term term, Process proc) {
        return term.toArg(proc).toInteger().toInt();
    }

    /**
     * A command carries its flags as a list of atoms, or as a literal wrapper
     * around that list depending on how the compiler emitted it.
     */
    private static boolean hasFlag(Term flagsTerm, String flag) {
        List flags;
        if (flagsTerm instanceof Tuple) {
            Tuple literal = (Tuple) flagsTerm;
            if (literal.arity() != 2) {
                return false;
            }
            flags = literal.element(2).toList();
        } else {
            flags = flagsTerm.toList();
        }
        Atom wanted = Atom.of(flag);
        while (flags.length() > 0) {
            if (wanted.equals(flags.head())) {
                return true;
            }
            flags = flags.tail();
        }
        return false;
    }

}
