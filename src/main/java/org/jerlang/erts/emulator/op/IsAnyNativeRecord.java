package org.jerlang.erts.emulator.op;

import org.jerlang.Module;
import org.jerlang.Process;
import org.jerlang.erts.emulator.Instruction;
import org.jerlang.type.List;
import org.jerlang.type.Term;

/**
 * Check whether Term is a native record. This runtime has no native record
 * values, so the test always fails.
 *
 * Example:
 * {is_any_native_record,{f,3},{x,0}}
 */
public class IsAnyNativeRecord {

    public static Term execute(Process proc, Module m, Instruction i, List params) {
        return i.arg(0);
    }

}
