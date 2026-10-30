package org.jerlang.erts.emulator.op;

import org.jerlang.Module;
import org.jerlang.Process;
import org.jerlang.erts.emulator.Instruction;
import org.jerlang.exception.ThrowException;
import org.jerlang.type.Integer;
import org.jerlang.type.List;
import org.jerlang.type.Term;

/**
 * Do a tail call to the function of arity Arity pointed to by Destination.
 * Do not update the CP register.
 *
 * The compiler emits call_ext_only for a tail call from a function that
 * allocated no stack words, so there is nothing to deallocate: the tail call
 * only replaces the activation itself.
 */
public class CallExtOnly {

    public static Term execute(Process proc, Module m, Instruction i, List params) throws ThrowException {
        // A tail call replaces this activation, so it has no continuation to
        // record.
        CallExt.execute(proc, m, i, params, false);
        // The callee returned into this stream; replace this activation.
        proc.popFrame();
        // A tail call returns to the continuation pointer of the function it
        // replaces. Index 0 means that function was entered from outside
        // this instruction stream, which ends the loop.
        Integer cp = Integer.of(proc.getCP());
        proc.setCP(0);
        return cp;
    }

}
