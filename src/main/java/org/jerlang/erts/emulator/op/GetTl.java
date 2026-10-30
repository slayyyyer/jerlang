package org.jerlang.erts.emulator.op;

import org.jerlang.Module;
import org.jerlang.Process;
import org.jerlang.erts.emulator.Instruction;
import org.jerlang.erts.erlang.Error;
import org.jerlang.type.List;
import org.jerlang.type.Term;

/**
 * Extract the tail of a non-empty list, without a guard.
 *
 * Arguments:
 * 1. Source
 * 2. Destination
 *
 * Example:
 * {get_tl,{x,0},{x,0}}
 */
public class GetTl {

    public static Term execute(Process proc, Module m, Instruction i, List params) {
        Term source = i.arg(0).toArg(proc);
        Term destination = i.arg(1);
        Term tail;
        if (source instanceof org.jerlang.type.ImproperList) {
            // A cons cell with a non-list tail is still matched by [_|T].
            // gen_server builds its timer list this way: [TRef | TimeoutMsg].
            tail = ((org.jerlang.type.ImproperList) source).tail();
        } else if (source.isList() && source.toList().length() > 0) {
            tail = source.toList().tail();
        } else {
            throw Error.badarg;
        }
        if (destination.isXRegister()) {
            proc.setX(destination.toRegisterIndex(), tail);
        } else if (destination.isYRegister()) {
            proc.setY(destination.toRegisterIndex(), tail);
        } else {
            throw new Error("Unsupported destination: " + destination);
        }
        return null;
    }

}
