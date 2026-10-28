package org.jerlang.erts.emulator.op;

import org.jerlang.Module;
import org.jerlang.Process;
import org.jerlang.erts.emulator.Instruction;
import org.jerlang.type.List;
import org.jerlang.type.Term;
import org.jerlang.type.Tuple;

/**
 * Create a tuple from the given elements and store it in Destination.
 *
 * Introduced in OTP 22. It replaces the put_tuple / put instruction pairs
 * that older compilers emitted.
 *
 * Arguments:
 * 1. Destination
 * 2. Elements
 */
public class PutTuple2 {

    public static Term execute(Process proc, Module m, Instruction i, List params) {
        Term destination = i.arg(0);
        List elements = i.arg(1).toList();
        Tuple tuple = new Tuple(elements.length());

        int index = 1;
        while (elements.length() > 0) {
            tuple.set(index++, resolve(proc, elements.head()));
            elements = elements.tail();
        }

        proc.setX(destination.toRegisterIndex(), tuple);
        return null;
    }

    private static Term resolve(Process proc, Term term) {
        if (term.isXRegister()) {
            return proc.getX(term.toRegisterIndex());
        } else if (term.isYRegister()) {
            return proc.getY(term.toRegisterIndex());
        } else {
            return term;
        }
    }

}
