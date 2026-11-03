package org.jerlang.erts.emulator.op;

import org.jerlang.Module;
import org.jerlang.Process;
import org.jerlang.erts.emulator.Instruction;
import org.jerlang.type.ImproperList;
import org.jerlang.type.List;
import org.jerlang.type.Term;

/**
 * Test the type of Arg1 and jump to Lbl if it is not a cons.
 *
 * Arguments:
 * 1. Lbl
 * 2. Arg1
 */
public class IsNonemptyList {

    public static Term execute(Process proc, Module m, Instruction i, List params) {
        Term lbl = i.arg(0);
        Term arg1 = i.arg(1).toArg(proc);

        if (arg1 instanceof ImproperList) {
            // An improper list is still a cons cell, so [H | T] matches it.
            return null;
        }

        if (arg1 instanceof List && arg1.toList().length() > 0) {
            return null;
        } else {
            return lbl;
        }
    }
}
