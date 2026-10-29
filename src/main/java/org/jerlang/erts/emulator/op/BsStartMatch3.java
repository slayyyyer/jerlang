package org.jerlang.erts.emulator.op;

import org.jerlang.Module;
import org.jerlang.Process;
import org.jerlang.erts.emulator.Instruction;
import org.jerlang.erts.erlang.Error;
import org.jerlang.type.BitString;
import org.jerlang.type.List;
import org.jerlang.type.Term;
import org.jerlang.type.stack.BinMatchState;

/**
 * Start a binary match. The source is either a bitstring, which starts a
 * fresh match, or an existing match context, which is copied so the two
 * matches do not share an offset.
 *
 * Arguments:
 * 1. Fail label
 * 2. Source, as a list with one element (as the BEAM stores it)
 * 3. Live registers
 * 4. Destination
 *
 * Example:
 * {test,bs_start_match3,{f,1},1,[{x,0}],{x,1}}
 */
public class BsStartMatch3 {

    public static Term execute(Process proc, Module m, Instruction i, List params) {
        Term source = i.arg(1);
        if (source instanceof List) {
            source = source.toList().head();
        }
        source = source.toArg(proc);

        BinMatchState context = start(source);
        if (context == null) {
            return i.arg(0);
        }

        write(proc, i.arg(3), context);
        return null;
    }

    /**
     * A matchable term is a bitstring or a match context. Returns null when
     * the term is neither, which is the fail path of the instruction.
     */
    static BinMatchState start(Term source) {
        if (source instanceof BinMatchState) {
            return new BinMatchState((BinMatchState) source);
        }
        if (source instanceof BitString) {
            return new BinMatchState((BitString) source, 0);
        }
        return null;
    }

    static void write(Process proc, Term destination, Term value) {
        if (destination.isXRegister()) {
            proc.setX(destination.toRegisterIndex(), value);
        } else if (destination.isYRegister()) {
            proc.setY(destination.toRegisterIndex(), value);
        } else {
            throw new Error("Unsupported destination: " + destination);
        }
    }

}
