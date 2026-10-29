package org.jerlang.erts.emulator.op;

import org.jerlang.Module;
import org.jerlang.Process;
import org.jerlang.erts.emulator.Instruction;
import org.jerlang.type.List;
import org.jerlang.type.Term;
import org.jerlang.type.TermOrder;

/**
 * Compare two terms and jump to Lbl if Arg1 is (numerically) equal to Arg2.
 *
 * Example:
 * {is_ne,{f,6},{x,0},0}
 */
public class IsNe {

    public static Term execute(Process proc, Module m, Instruction i, List params) {
        Term a = i.arg(1).toArg(proc);
        Term b = i.arg(2).toArg(proc);

        // /= compares numbers by value: 1 /= 1.0 is false.
        if (TermOrder.equal(a, b)) {
            return i.arg(0);
        } else {
            return null;
        }
    }

}
