package org.jerlang.erts.erlang;

import java.util.Arrays;

import org.jerlang.FunctionSignature;
import org.jerlang.Interpreter;
import org.jerlang.Module;
import org.jerlang.ModuleRegistry;
import org.jerlang.Process;
import org.jerlang.ProcessRegistry;
import org.jerlang.type.Fun;
import org.jerlang.type.List;
import org.jerlang.type.Term;
import org.jerlang.type.stack.StackFrame;

/**
 * Applies an Erlang fun from Java code, which is what the built-in library
 * functions that take a callback need (maps:fold/3 and friends).
 *
 * A fun body is an instruction stream of its own, so it runs as a nested
 * activation of the calling process: its free variables go in the first X
 * registers, the arguments after them, and it ends with the `return` of its
 * own entry label.
 *
 * The caller's X registers are restored afterwards, because a BIF call leaves
 * the live registers of the function that called it alone; only X0 changes,
 * to the result.
 */
public final class FunApply {

    private FunApply() {
    }

    public static Term apply(Term term, List args) {
        Fun fun = term.toFun();
        Process process = (Process) ProcessRegistry.self();
        if (process == null) {
            throw new Error("No process to apply " + fun + " in");
        }
        FunctionSignature signature = fun.signature();
        Module module = ModuleRegistry.get(signature.module());
        if (module == null) {
            throw Error.badarg;
        }

        Term[] saved = Arrays.copyOf(process.registers(), process.registers().length);
        // The BEAM calling convention for a fun: the arguments in X0..X(A-1),
        // then the free variables captured when the fun was created, at X(A)
        // and up. call_fun2 sets them up the same way.
        int index = 0;
        List remaining = args;
        while (remaining.length() > 0) {
            process.setX(index++, remaining.head());
            remaining = remaining.tail();
        }
        for (Term free : fun.savedRegisters()) {
            process.setX(index++, free);
        }

        process.pushSignature(signature);
        int callerCP = process.getCP();
        process.setCP(0);
        boolean ownFrame = !fun.isExternal();
        if (ownFrame) {
            process.pushFrame(new StackFrame(signature.module(), signature.function(),
                signature.fun_arity().toInt()));
        }
        Term result;
        try {
            if (fun.isExternal()) {
                // fun M:F/A: the callee pushes its own frame in Module.apply.
                result = module.apply(signature, args);
            } else {
                result = Interpreter.continueExecutionAtLabel(module, fun.label());
            }
        } finally {
            process.setCP(callerCP);
            process.popSignature();
            System.arraycopy(saved, 0, process.registers(), 0, saved.length);
        }
        return result;
    }

}
