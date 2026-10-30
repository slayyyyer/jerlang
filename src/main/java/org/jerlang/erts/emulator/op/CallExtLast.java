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
 *
 * A tail call replaces the current activation, so the words this function
 * allocated have to be released and the continuation pointer of the function
 * it returns to has to be restored. Both happen after the callee ran: the
 * callee's own `return` ended its instruction stream already, and a raise
 * inside it captured a stacktrace that still has to contain this activation.
 *
 * Arguments:
 * 1. Arity
 * 2. Destination
 * 3. Deallocate
 *
 * Example:
 * {call_ext_last,1,{extfunc,erlang,spawn,3},2}
 */
public class CallExtLast {

    public static Term execute(Process process, Module m, Instruction i, List params) throws ThrowException {
        Integer deallocate = i.arg(2).toInteger();
        // A tail call replaces this activation, so it has no continuation to
        // record: what has to be resumed is this function's own caller.
        CallExt.execute(process, m, i, params, false);
        // A tail call replaces the current activation: drop the frame, then
        // the Y register words, then return to where this function would have
        // returned. Only a function that allocated saved a continuation
        // pointer, which is the same rule call_last follows.
        process.popFrame();
        if (deallocate.toInt() > 0) {
            process.restoreCP();
        }
        process.deallocate(deallocate.toInt());
        // Index 0 means the activation was entered from outside this
        // instruction stream (Java, or the process entry point): there is no
        // continuation to jump to.
        Integer cp = Integer.of(process.getCP());
        process.setCP(0);
        return cp;
    }

}
