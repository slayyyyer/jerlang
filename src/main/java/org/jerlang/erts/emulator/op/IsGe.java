package org.jerlang.erts.emulator.op;

import org.jerlang.Module;
import org.jerlang.Process;
import org.jerlang.erts.emulator.Instruction;
import org.jerlang.type.List;
import org.jerlang.type.Term;
import org.jerlang.type.TermOrder;

/**
 * is greater or equal
 *
 * Arguments:
 * 1. Lbl
 * 2. Arg1
 * 3. Arg2
 *
 * Compare two terms and jump to Lbl if Arg1 is less than Arg2.
 *
 * Both operands may be literals, X registers or Y registers; the compiler
 * uses is_ge as the failing test for &lt; and &lt;=.
 */
public class IsGe {

    public static Term execute(Process proc, Module m, Instruction i, List params) {
        Term arg1 = i.arg(1).toArg(proc);
        Term arg2 = i.arg(2).toArg(proc);
        if (TermOrder.compare(arg1, arg2) < 0) {
            return i.arg(0);
        } else {
            return null;
        }
    }

}
