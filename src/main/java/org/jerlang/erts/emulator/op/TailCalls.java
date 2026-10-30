package org.jerlang.erts.emulator.op;

import org.jerlang.FunctionSignature;
import org.jerlang.Interpreter;
import org.jerlang.Module;
import org.jerlang.Process;
import org.jerlang.erts.erlang.Error;
import org.jerlang.type.Str;
import org.jerlang.type.stack.StackFrame;

/**
 * Turn a cross-module tail call into a change of instruction stream in the
 * running interpreter loop, instead of a nested Java call.
 *
 * A tail call replaces the current activation. The Java frame it used to nest
 * would make a long B -> C -> B loop grow the JVM stack and hide the process
 * from the scheduler, so the loop switches module and index in place. The
 * place the replaced activation returns to is recorded explicitly, and a
 * resume, a wait or a park carries those continuations along.
 */
final class TailCalls {

    private TailCalls() {
    }

    /**
     * Whether the target runs BEAM code and the call may be trampolined. A
     * tail call cannot leave an active catch frame in BEAM, but if one is
     * present the old nested path is still correct.
     */
    static boolean canSwitch(Process process, Module target, FunctionSignature signature) {
        return target != null
            && target.hasInstructionStream(signature)
            && process.exceptionHandler() == null;
    }

    /**
     * Replace the current activation with the target's instruction stream.
     * The caller has already restored the continuation pointer and dropped the
     * stack words the replaced activation owned.
     */
    static void switchTo(Process process, Module current, Module target,
        FunctionSignature signature, int returnCP) {
        if (!target.hasFunction(signature)) {
            throw new Error(Str.of("Invalid function: " + signature));
        }
        int start = Interpreter.functionStart(target, signature);
        if (returnCP > 0) {
            // A local call in the replaced activation has to resume once the
            // chain that starts here ends.
            process.pushTailTarget(current, returnCP);
        }
        process.setCP(0);
        process.popFrame();
        process.pushFrame(new StackFrame(signature.module(), signature.function(),
            signature.fun_arity().toInt()));
        process.requestTailCall(target, start);
    }

}
