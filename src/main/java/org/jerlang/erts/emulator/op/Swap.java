package org.jerlang.erts.emulator.op;

import org.jerlang.Module;
import org.jerlang.Process;
import org.jerlang.erts.emulator.Instruction;
import org.jerlang.erts.erlang.Error;
import org.jerlang.type.List;
import org.jerlang.type.Term;

/**
 * Swap the contents of two registers.
 *
 * Arguments:
 * 1. Register
 * 2. Register
 *
 * Example:
 * {swap,{x,0},{x,1}}
 */
public class Swap {

    public static Term execute(Process proc, Module m, Instruction i, List params) {
        Term a = i.arg(0);
        Term b = i.arg(1);
        Term valueA = get(proc, a);
        Term valueB = get(proc, b);
        set(proc, a, valueB);
        set(proc, b, valueA);
        return null;
    }

    static Term get(Process proc, Term register) {
        if (register.isXRegister()) {
            return proc.getX(register.toRegisterIndex());
        } else if (register.isYRegister()) {
            return proc.getY(register.toRegisterIndex());
        } else {
            throw new Error("Unsupported register: " + register);
        }
    }

    static void set(Process proc, Term register, Term value) {
        if (register.isXRegister()) {
            proc.setX(register.toRegisterIndex(), value);
        } else if (register.isYRegister()) {
            proc.setY(register.toRegisterIndex(), value);
        } else {
            throw new Error("Unsupported register: " + register);
        }
    }

}
