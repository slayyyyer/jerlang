package org.jerlang.erts.emulator.op;

import org.jerlang.Module;
import org.jerlang.Process;
import org.jerlang.erts.emulator.Instruction;
import org.jerlang.erts.erlang.Error;
import org.jerlang.type.List;
import org.jerlang.type.Term;
import org.jerlang.type.stack.BinMatchState;

/**
 * Take everything that is left in the match as a binary, or as a bitstring
 * when it is not a whole number of bytes. The compiler also uses this on the
 * failure path, after restoring the start position, to build the original
 * value for the badmatch reason.
 *
 * Arguments:
 * 1. Match context
 * 2. Destination
 * 3. Live registers
 *
 * Example:
 * {bs_get_tail,{x,1},{x,0},2}
 */
public class BsGetTail {

    public static Term execute(Process proc, Module m, Instruction i, List params) {
        BinMatchState context = i.arg(0).toArg(proc).toBinMatchState();
        Term tail = context.readTail(1);
        if (tail == null) {
            throw new Error("Can not take the tail of a match context");
        }
        BsStartMatch3.write(proc, i.arg(1), tail);
        return null;
    }

}
