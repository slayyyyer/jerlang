package org.jerlang.erts.emulator.op;

import org.jerlang.Module;
import org.jerlang.Process;
import org.jerlang.erts.emulator.Instruction;
import org.jerlang.type.List;
import org.jerlang.type.Term;
import org.jerlang.type.Tuple;

/**
 * Test whether Arg1 is a tuple with the given arity and first element.
 * Jumps to the failure label if it is not.
 *
 * Emitted for record tests and for tuple patterns such as
 * `receive {echo, Reply} -> ... end`.
 */
public class IsTaggedTuple {

    public static Term execute(Process proc, Module m, Instruction i, List params) {
        Term fail = i.arg(0);
        Term arg = i.arg(1).toArg(proc);
        int arity = i.arg(2).toInteger().toInt();
        Term tag = i.arg(3).toArg(proc);

        if (arg instanceof Tuple) {
            Tuple tuple = (Tuple) arg;
            if (tuple.arity() == arity && tuple.element(1).equals(tag)) {
                return null;
            }
        }
        return fail;
    }

}
