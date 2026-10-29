package org.jerlang.erts.emulator.op;

import org.jerlang.FunctionSignature;
import org.jerlang.Module;
import org.jerlang.Process;
import org.jerlang.erts.emulator.Instruction;
import org.jerlang.type.Integer;
import org.jerlang.type.List;
import org.jerlang.type.Term;
import org.jerlang.type.stack.StackFrame;

/**
 * Deallocate and do a tail recursive call to the function at Label.
 * Do not update the CP register.
 * Before the call deallocate Deallocate words of stack.
 *
 * Arguments:
 * 1. Arity
 * 2. Label
 * 3. Deallocate
 *
 * Example:
 * {call_last,2,{f,4},1}
 */
public class CallLast {

    public static Term execute(Process proc, Module m, Instruction i, List params) {
        Integer deallocate = i.arg(2).toInteger();
        // A tail call replaces the current activation. Allocate saved the
        // continuation pointer of this frame, so the replaced activation
        // has to drop it; call_last only occurs when a frame was allocated.
        if (deallocate.toInt() > 0) {
            proc.restoreCP();
        }
        proc.popFrame();
        FunctionSignature callee = m.functionAtLabel(i.arg(1).toRegisterIndex().toInt());
        if (callee != null) {
            proc.pushFrame(new StackFrame(callee.module(), callee.function(),
                callee.fun_arity().toInt()));
        }
        proc.deallocate(deallocate.toInt());
        return i.arg(1);
    }

}
