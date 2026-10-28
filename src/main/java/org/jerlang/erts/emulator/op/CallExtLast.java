package org.jerlang.erts.emulator.op;

import org.jerlang.Module;
import org.jerlang.Process;
import org.jerlang.erts.emulator.Instruction;
import org.jerlang.exception.ThrowException;
import org.jerlang.type.Integer;
import org.jerlang.type.List;
import org.jerlang.type.Term;

/**
 * Deallocate and do a tail call to function of arity Arity
 * pointed to by Destination.
 * Do not update the CP register.
 * Deallocate Deallocate words from the stack before the call.
 */
public class CallExtLast {

    public static Term execute(Process process, Module m, Instruction i, List params) throws ThrowException {
        CallExt.execute(process, m, i, params);
        if (process.applyDepth() > 0) {
            // Applied synchronously from Java: this invocation has no
            // instruction stream of its own to return into.
            return Integer.of(0);
        }
        // A tail call returns to the continuation pointer of the calling
        // function. Index 0 means there is no caller left.
        Integer cp = Integer.of(process.getCP());
        process.setCP(0);
        return cp;
    }

}
