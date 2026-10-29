package org.jerlang.erts.emulator.op;

import org.jerlang.Module;
import org.jerlang.Process;
import org.jerlang.erts.emulator.Instruction;
import org.jerlang.type.Integer;
import org.jerlang.type.List;
import org.jerlang.type.Term;
import org.jerlang.type.stack.BinMatchState;

/**
 * Store the current match position, so a failed match can restart from it.
 *
 * Arguments:
 * 1. Match context
 * 2. Destination
 * 3. Live registers
 *
 * Example:
 * {bs_get_position,{x,1},{x,0},2}
 */
public class BsGetPosition {

    public static Term execute(Process proc, Module m, Instruction i, List params) {
        BinMatchState context = i.arg(0).toArg(proc).toBinMatchState();
        BsStartMatch3.write(proc, i.arg(1), Integer.of(context.position()));
        return null;
    }

}
