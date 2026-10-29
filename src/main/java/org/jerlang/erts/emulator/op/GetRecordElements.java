package org.jerlang.erts.emulator.op;

import org.jerlang.Module;
import org.jerlang.Process;
import org.jerlang.erts.emulator.Instruction;
import org.jerlang.type.List;
import org.jerlang.type.Term;

/**
 * Extract the values of native record fields. This runtime has no native
 * record values, so the extraction always fails.
 *
 * Example:
 * {get_record_elements,{f,3},{x,0},{list,[{atom,a},{x,1}]}}
 */
public class GetRecordElements {

    public static Term execute(Process proc, Module m, Instruction i, List params) {
        return i.arg(0);
    }

}
