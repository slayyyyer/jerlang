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

        // The head can be a literal (OTP 29 uses a literal tuple when
        // building an option list); the tail can be a literal list or a
        // register holding one.
        Term head = headArg.toArg(proc);
        List tail = tailArg.toArg(proc).toList();
        List list = new List(head, tail);
        if (destination.isXRegister()) {
            proc.setX(destination.toRegisterIndex(), list);
        } else if (destination.isYRegister()) {
            proc.setY(destination.toRegisterIndex(), list);
        } else {
            throw new Error("Unsupported: " + i);
        }
        return null;
    }

}
