package org.jerlang.erts.emulator.op;

import org.jerlang.Module;
import org.jerlang.Process;
import org.jerlang.erts.emulator.Instruction;
import org.jerlang.type.List;
import org.jerlang.type.Term;

/**
 * Set the current source location of the running function.
 *
 * The operand is either an index into the module's line table (older
 * compilers) or an inline [{location,Filename,Line}] (OTP 27 and later).
 */
public class Line {

    public static Term execute(Process process, Module m, Instruction i, List params) {
        SourceLocation.set(process, m, i.arg(0));
        return null;
    }

}
