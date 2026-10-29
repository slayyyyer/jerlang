package org.jerlang.erts.emulator.op;

import org.jerlang.Module;
import org.jerlang.Process;
import org.jerlang.erts.emulator.Instruction;
import org.jerlang.erts.erlang.Error;
import org.jerlang.type.List;
import org.jerlang.type.Term;

/**
 * Extract the head of a non-empty list, without a guard.
 *
 * Arguments:
 * 1. Source
 * 2. Destination
 *
 * Example:
 * {get_hd,{x,0},{x,0}}
 */
public class GetHd {

    public static Term execute(Process proc, Module m, Instruction i, List params) {
        Term source = i.arg(0).toArg(proc);
        Term destination = i.arg(1);
        if (!source.isList() || source.toList().length() == 0) {
            throw Error.badarg;
        }
        Term head = source.toList().head();
        if (destination.isXRegister()) {
            proc.setX(destination.toRegisterIndex(), head);
        } else if (destination.isYRegister()) {
            proc.setY(destination.toRegisterIndex(), head);
        } else {
            throw new Error("Unsupported destination: " + destination);
        }
        return null;
    }

}
