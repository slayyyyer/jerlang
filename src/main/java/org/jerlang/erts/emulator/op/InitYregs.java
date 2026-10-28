package org.jerlang.erts.emulator.op;

import org.jerlang.Module;
import org.jerlang.Process;
import org.jerlang.erts.emulator.Instruction;
import org.jerlang.type.List;
import org.jerlang.type.Term;
import org.jerlang.type.Tuple;

/**
 * Set the given Y registers to nil.
 *
 * Introduced in OTP 22, where the compiler emits init_yregs instead of
 * initializing Y registers with individual move instructions.
 *
 * http://www.erlang.org/doc/apps/erts/beamasm.html
 */
public class InitYregs {

    public static Term execute(Process process, Module m, Instruction i, List params) {
        List registers = i.arg(0).toList();
        while (registers.length() > 0) {
            Tuple register = registers.head().toTuple();
            process.setY(register.element(2).toInteger(), List.nil);
            registers = registers.tail();
        }
        return null;
    }

}
