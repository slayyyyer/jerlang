package org.jerlang.erts.emulator.op;

import org.jerlang.FunctionSignature;
import org.jerlang.Interpreter;
import org.jerlang.Module;
import org.jerlang.ModuleRegistry;
import org.jerlang.Process;
import org.jerlang.ProcessState;
import org.jerlang.erts.emulator.Instruction;
import org.jerlang.type.Fun;
import org.jerlang.type.Term;
import org.jerlang.type.stack.StackFrame;

/**
 * Calling a fun.
 *
 * A lambda's label belongs to the module it was compiled in, and an
 * instruction stream can only resolve labels of its own module. A fun from
 * another module therefore runs as its own activation, the same way a
 * cross-module call does; one from the current module is still reached by
 * jumping to its label, which avoids a Java frame per fun call.
 *
 * The caller has already put the arguments in X0..X(Arity-1) and the free
 * variables in the registers after them.
 */
final class FunCall {

    private FunCall() {
    }

    static boolean isLocal(Module m, Fun fun) {
        return fun.signature().module().equals(m.name());
    }

    static Term call(Process proc, Module m, Instruction i, Fun fun) {
        FunctionSignature signature = fun.signature();
        Module target = ModuleRegistry.get(signature.module());
        proc.pushFrame(new StackFrame(signature.module(), signature.function(),
            signature.fun_arity().toInt()));
        int callerCP = proc.getCP();
        // The lambda has no continuation in another module's stream; it ends
        // when its own instruction stream returns.
        proc.setCP(0);
        proc.pushResumePoint(m, i.index() + 1);
        Term result;
        try {
            result = Interpreter.continueExecutionAtLabel(target, fun.label());
        } finally {
            if (proc.state() != ProcessState.WAITING) {
                proc.popResumePoint();
                proc.setCP(callerCP);
            }
        }
        proc.setX(0, result);
        return null;
    }

}
