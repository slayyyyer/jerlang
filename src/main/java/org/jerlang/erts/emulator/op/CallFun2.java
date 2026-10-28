package org.jerlang.erts.emulator.op;

import org.jerlang.Module;
import org.jerlang.Process;
import org.jerlang.erts.emulator.Instruction;
import org.jerlang.type.Fun;
import org.jerlang.type.Integer;
import org.jerlang.type.List;
import org.jerlang.type.Term;

/**
 * Call a fun of arity Arity. Arguments are in X0 to X(Arity-1) and the fun
 * is in X(Arity).
 *
 * Introduced in OTP 24. The first argument is a tag that tells the runtime
 * whether the call is known to be local, and the third carries type
 * information for the JIT; neither is needed here.
 *
 * Arguments:
 * 1. Tag
 * 2. Arity
 * 3. Type information
 *
 * Example:
 * {call_fun2,{atom,safe},1,{tr,{x,1},{t_fun,any,any,any}}}
 */
public class CallFun2 {

    public static Term execute(Process proc, Module m, Instruction i, List params) {
        Integer arity = i.arg(1).toInteger();
        Fun fun = proc.getX(arity).toFun();

        // Restore the free variables the fun was created with; they follow
        // the arguments in the X registers.
        int registerIndex = arity.toInt();
        for (int index = 0; index < fun.savedRegisters().length; index++) {
            proc.setX(registerIndex + index, fun.savedRegisters()[index]);
        }

        // In this interpreter the continuation pointer is the index of the
        // call instruction itself; the loop advances past it.
        proc.setCP(i.index());
        return fun.label();
    }

}
