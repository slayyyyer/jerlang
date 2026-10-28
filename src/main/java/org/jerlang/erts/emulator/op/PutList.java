package org.jerlang.erts.emulator.op;

import org.jerlang.Module;
import org.jerlang.Process;
import org.jerlang.erts.emulator.Instruction;
import org.jerlang.type.List;
import org.jerlang.type.Term;

/**
 * Create a list cell from Head and Tail and store it in Destination.
 *
 * Arguments:
 * 1. Head
 * 2. Tail
 * 3. Destination
 */
public class PutList {

    public static Term execute(Process proc, Module m, Instruction i, List params) {
        Term headArg = i.arg(0);
        Term tailArg = i.arg(1);
        Term destination = i.arg(2);

        if (!destination.isXRegister()) {
            throw new Error("Unsupported: " + i);
        }

        Term head;
        if (headArg.isXRegister()) {
            head = proc.getX(headArg.toRegisterIndex());
        } else if (headArg.isYRegister()) {
            head = proc.getY(headArg.toRegisterIndex());
        } else {
            throw new Error("Unsupported: " + i);
        }

        List tail;
        if (tailArg.isXRegister()) {
            tail = proc.getX(tailArg.toRegisterIndex()).toList();
        } else if (tailArg.isYRegister()) {
            tail = proc.getY(tailArg.toRegisterIndex()).toList();
        } else if (tailArg.isList()) {
            tail = tailArg.toList();
        } else {
            throw new Error("Unsupported: " + i);
        }

        proc.setX(destination.toRegisterIndex(), new List(head, tail));
        return null;
    }

}
