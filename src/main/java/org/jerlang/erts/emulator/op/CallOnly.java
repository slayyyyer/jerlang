package org.jerlang.erts.emulator.op;

import org.jerlang.FunctionSignature;
import org.jerlang.Module;
import org.jerlang.Process;
import org.jerlang.erts.emulator.Instruction;
import org.jerlang.type.List;
import org.jerlang.type.Term;
import org.jerlang.type.stack.StackFrame;

/**
 * Do a tail recursive call to the function at Label.
 * Do not update the CP register.
 *
 * Arguments:
 * 1. Arity
 * 2. Label
 */
public class CallOnly {

    public static Term execute(Process process, Module m, Instruction i, List params) {
        FunctionSignature callee = m.functionAtLabel(i.arg(1).toRegisterIndex().toInt());
        // A tail call replaces the current activation.
        process.popFrame();
        if (callee != null) {
            process.pushFrame(new StackFrame(callee.module(), callee.function(),
                callee.fun_arity().toInt()));
        }
        return i.arg(1);
    }

}
