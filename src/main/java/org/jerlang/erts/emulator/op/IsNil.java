package org.jerlang.erts.emulator.op;

import org.jerlang.Module;
import org.jerlang.Process;
import org.jerlang.erts.emulator.Instruction;
import org.jerlang.type.List;
import org.jerlang.type.Term;

/**
 * Test the type of Arg1 and jump to Lbl if it is not nil.
 *
 * Arguments:
 * 1. Lbl
 * 2. Arg1
 */
public class IsNil {

    public static Term execute(Process proc, Module m, Instruction i, List params) {
        Term lbl = i.arg(0);
        Term arg1 = i.arg(1).toArg(proc);

        if (List.nil.equals(arg1)) {
            return null;
        } else {
            return lbl;
        }
    }

}
