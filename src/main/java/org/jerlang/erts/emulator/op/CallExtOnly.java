package org.jerlang.erts.emulator.op;

import org.jerlang.Module;
import org.jerlang.Process;
import org.jerlang.erts.emulator.Instruction;
import org.jerlang.exception.ThrowException;
import org.jerlang.type.Integer;
import org.jerlang.type.List;
import org.jerlang.type.Term;

/**
 * Do a tail recursive call to the function at Label.
 * Do not update the CP register.
 */
public class CallExtOnly {

    public static Term execute(Process proc, Module m, Instruction i, List params) throws ThrowException {
        CallExt.execute(proc, m, i, params);
        if (proc.applyDepth() > 0) {
            // Applied synchronously from Java: this invocation has no
            // instruction stream of its own to return into.
            return Integer.of(0);
        }
        // A tail call does not update the continuation pointer, it returns
        // to the one of the calling function. Index 0 means there is no
        // caller left, which ends the process.
        Integer cp = Integer.of(proc.getCP());
        proc.setCP(0);
        return cp;
    }

}
