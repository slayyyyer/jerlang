package org.jerlang.erts.emulator.op;

import org.jerlang.Module;
import org.jerlang.Process;
import org.jerlang.erts.emulator.Instruction;
import org.jerlang.type.List;
import org.jerlang.type.Term;

/**
 * Compare two terms and jump to Lbl if Arg1 is exactly equal to Arg2.
 *
 * Exactly equal means 1 =/= 1.0, unlike ==. Both operands may be literals,
 * X registers or Y registers.
 */
public class IsNeExact {

    public static Term execute(Process proc, Module m, Instruction i, List params) {
        Term arg1 = i.arg(1).toArg(proc);
        Term arg2 = i.arg(2).toArg(proc);
        if (arg1.equals(arg2)) {
            return i.arg(0);
        } else {
            return null;
        }
    }

}
