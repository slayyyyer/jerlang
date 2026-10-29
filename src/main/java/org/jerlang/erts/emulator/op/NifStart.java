package org.jerlang.erts.emulator.op;

import org.jerlang.Module;
import org.jerlang.Process;
import org.jerlang.erts.emulator.Instruction;
import org.jerlang.type.List;
import org.jerlang.type.Term;

/**
 * No-op at the start of a function declared in -nifs().
 */
public class NifStart {

    public static Term execute(Process proc, Module m, Instruction i, List params) {
        return null;
    }

}
