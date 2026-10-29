package org.jerlang.erts.emulator.op;

import org.jerlang.Module;
import org.jerlang.Process;
import org.jerlang.erts.emulator.Instruction;
import org.jerlang.type.List;
import org.jerlang.type.Term;

/**
 * Provide a location for an executable line.
 *
 * Arguments:
 * 1. Location
 * 2. Index
 *
 * Example:
 * {executable_line,[{location,"m.erl",7}],3}
 */
public class ExecutableLine {

    public static Term execute(Process process, Module m, Instruction i, List params) {
        SourceLocation.set(process, m, i.arg(0));
        return null;
    }

}
