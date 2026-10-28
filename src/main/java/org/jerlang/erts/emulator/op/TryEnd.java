package org.jerlang.erts.emulator.op;

import org.jerlang.Module;
import org.jerlang.Process;
import org.jerlang.erts.emulator.Instruction;
import org.jerlang.erts.erlang.Error;
import org.jerlang.type.List;
import org.jerlang.type.Term;
import org.jerlang.type.stack.ExceptionHandler;

/**
 * We have passed a try block without exception,
 * so we can remove the exception handler from the stack.
 */
public class TryEnd {

    public static Term execute(Process proc, Module m, Instruction i, List params) {
        Term source = i.arg(0);
        if (source.isYRegister()) {
            // Leaving the protected block: the enclosing handler becomes
            // current again.
            Term handler = source.toArg(proc);
            if (handler instanceof ExceptionHandler) {
                proc.setExceptionHandler(((ExceptionHandler) handler).previous());
            } else {
                proc.setExceptionHandler(null);
            }
            return null;
        } else {
            throw new Error("Not implemented: " + i);
        }
    }

}
