package org.jerlang.erts.emulator.op;

import org.jerlang.Module;
import org.jerlang.Process;
import org.jerlang.erts.emulator.Instruction;
import org.jerlang.type.List;
import org.jerlang.type.Term;

/**
 * Define a function M:F/A.
 *
 * This instruction starts the function entry block, but calls and the
 * export table jump to the label after it, so it is not executed. Stack
 * frames are pushed by the call instructions and by Module.apply.
 */
public class FuncInfo {

    public static Term execute(Process process, Module m, Instruction i, List params) {
        return null;
    }

}
