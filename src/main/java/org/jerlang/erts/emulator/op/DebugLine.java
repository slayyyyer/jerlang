package org.jerlang.erts.emulator.op;

import org.jerlang.Module;
import org.jerlang.Process;
import org.jerlang.erts.emulator.Instruction;
import org.jerlang.type.List;
import org.jerlang.type.Term;

/**
 * Provide a location for a place where a break point can be placed.
 *
 * Arguments:
 * 1. Kind (entry or line)
 * 2. Location
 * 3. Index
 * 4. Live
 *
 * Example:
 * {debug_line,{atom,line},[{location,"m.erl",7}],3,2}
 */
public class DebugLine {

    public static Term execute(Process process, Module m, Instruction i, List params) {
        SourceLocation.set(process, m, i.arg(1));
        return null;
    }

}
