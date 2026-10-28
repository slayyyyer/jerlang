package org.jerlang.erts.emulator.op;

import org.jerlang.Module;
import org.jerlang.Process;
import org.jerlang.erts.emulator.Instruction;
import org.jerlang.type.List;
import org.jerlang.type.Term;
import org.jerlang.type.stack.ExceptionHandler;

/**
 * Enter a `try ... catch` clause after an exception.
 *
 * The handler stored on the stack belongs to the try that failed, so the
 * handler of the enclosing try becomes current again and the slot is
 * cleared.
 *
 * Example:
 * {try_case,{y,0}}
 */
public class TryCase {

    public static Term execute(Process proc, Module m, Instruction i, List params) {
        Term source = i.arg(0).toArg(proc);
        if (source instanceof ExceptionHandler) {
            ExceptionHandler eh = source.toExceptionHandler();
            proc.setExceptionHandler(eh.previous());
            proc.setY(i.arg(0).toRegisterIndex(), List.nil);
        }
        return null;
    }

}
