package org.jerlang.erts.emulator.op;

import org.jerlang.Module;
import org.jerlang.Process;
import org.jerlang.erts.emulator.Instruction;
import org.jerlang.type.List;
import org.jerlang.type.Term;

/**
 * Move the source Source (a literal or a register) to
 * the destination register Destination.
 */
public class Move {

    public static Term execute(Process proc, Module m, Instruction i, List params) {
        // toArg resolves X, Y and float registers and returns a literal
        // unchanged. A literal tuple such as {ok, Value} is not mistaken for
        // a register, which used to make every literal 2-tuple unreadable.
        Term source = i.arg(0).toArg(proc);
        Term destination = i.arg(1);

        if (destination.isXRegister()) {
            proc.setX(destination.toRegisterIndex(), source);
        } else if (destination.isYRegister()) {
            proc.setY(destination.toRegisterIndex(), source);
        } else {
            throw new Error("Unsupported destination: " + destination);
        }
        return null;
    }

}
