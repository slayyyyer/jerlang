package org.jerlang.erts.emulator.op;

import org.jerlang.Module;
import org.jerlang.Process;
import org.jerlang.erts.emulator.Instruction;
import org.jerlang.type.List;
import org.jerlang.type.Term;
import org.jerlang.type.stack.BinMatchState;

/**
 * Restore a position saved by bs_get_position.
 *
 * Arguments:
 * 1. Match context
 * 2. Position
 *
 * Example:
 * {bs_set_position,{x,1},{x,0}}
 */
public class BsSetPosition {

    public static Term execute(Process proc, Module m, Instruction i, List params) {
        BinMatchState context = i.arg(0).toArg(proc).toBinMatchState();
        int position = i.arg(1).toArg(proc).toInteger().toInt();
        context.setPosition(position);
        return null;
    }

}
