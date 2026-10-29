package org.jerlang.erts.emulator.op;

import org.jerlang.Module;
import org.jerlang.Process;
import org.jerlang.erts.emulator.Instruction;
import org.jerlang.type.List;
import org.jerlang.type.Term;

/**
 * Check whether the fields of a native record are accessible. This runtime
 * has no native record values, so the check always fails.
 *
 * Example:
 * {is_record_accessible,{f,3},{x,0},{atom,external}}
 */
public class IsRecordAccessible {

    public static Term execute(Process proc, Module m, Instruction i, List params) {
        return i.arg(0);
    }

}
