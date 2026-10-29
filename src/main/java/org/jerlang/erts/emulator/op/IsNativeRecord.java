package org.jerlang.erts.emulator.op;

import org.jerlang.Module;
import org.jerlang.Process;
import org.jerlang.erts.emulator.Instruction;
import org.jerlang.type.List;
import org.jerlang.type.Term;

/**
 * Check whether Rec is the native record Name defined in Module. This
 * runtime has no native record values, so the test always fails.
 *
 * Example:
 * {is_native_record,{f,3},{x,0},{atom,m},{atom,r}}
 */
public class IsNativeRecord {

    public static Term execute(Process proc, Module m, Instruction i, List params) {
        return i.arg(0);
    }

}
