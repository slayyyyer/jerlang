package org.jerlang.erts.emulator.op;

import org.jerlang.FunctionSignature;
import org.jerlang.Module;
import org.jerlang.ModuleRegistry;
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
 * A tail call replaces the current activation: the words this function
 * allocated are released, and the continuation pointer of the function it
 * returns to is restored. The words have to go before the callee runs,
 * because the callee allocates its own Y registers on top of the stack and
 * may park in a receive, in which case it is resumed later with exactly those
 * registers. Releasing them afterwards cut the parked callee's variables away.
 *
 * The stack frame itself is different: a BEAM callee is an activation of its
 * own, so this frame is dropped before it starts and does not appear in a
 * raise from inside it, as in OTP. A built-in callee runs inside this frame,
 * so its frame is only dropped afterwards, and a raise from the builtin still
 * reports this function.
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
        // The function allocated, so its continuation pointer is on the CP
        // stack. A tail call replaces that frame and returns to the pointer
        // saved by the caller's allocate, which is a restore even when the
        // deallocate operand is zero: then the CP is the only word to drop.
        // Skipping it leaked the caller's continuation and the outer frame
        // later returned into its own middle, with whatever X0 held by then.
        process.restoreCP();
        process.deallocate(deallocate.toInt());

        FunctionSignature signature = CallExt.resolve(m, i.arg(1).toInteger());
        Module target = ModuleRegistry.get(signature.module());
        boolean instructionStream = target != null && target.hasInstructionStream(signature);
        if (instructionStream && process.exceptionHandler() == null) {
            // A tail call replaces this activation. Continue in the target's
            // stream instead of nesting another interpreter frame.
            TailCalls.switchTo(process, m, target, signature, process.getCP());
            return null;
        }
        if (instructionStream) {
            process.popFrame();
        }

        // The callee runs its own instruction stream, and this activation has
        // already been replaced, so there is no continuation to record.
        CallExt.execute(process, m, i, params, false);

        if (!instructionStream) {
            process.popFrame();
        }

        // Index 0 means the activation was entered from outside this
        // instruction stream (Java, or the process entry point): there is no
        // continuation to jump to.
        Integer cp = Integer.of(process.getCP());
        process.setCP(0);
        return cp;
    }

}
