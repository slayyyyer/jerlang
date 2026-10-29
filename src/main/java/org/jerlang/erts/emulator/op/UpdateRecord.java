package org.jerlang.erts.emulator.op;

import org.jerlang.Module;
import org.jerlang.Process;
import org.jerlang.erts.emulator.Instruction;
import org.jerlang.erts.erlang.Error;
import org.jerlang.type.Atom;
import org.jerlang.type.List;
import org.jerlang.type.Term;
import org.jerlang.type.Tuple;

/**
 * Set Dst to a copy of Src with the update list applied.
 *
 * Arguments:
 * 1. Hint ({atom,copy} or {atom,reuse})
 * 2. Size of the tuple
 * 3. Src
 * 4. Dst
 * 5. Updates: a flat list of [Index, Value] pairs
 *
 * Example:
 * {update_record,{atom,reuse},3,{x,0},{x,0},{list,[2,{x,1}]}}
 */
public class UpdateRecord {

    public static Term execute(Process proc, Module m, Instruction i, List params) {
        int size = i.arg(1).toInteger().toInt();
        Term source = i.arg(2).toArg(proc);
        Term destination = i.arg(3);
        List updates = i.arg(4).toArg(proc).toList();

        if (!(source instanceof Tuple) || source.toTuple().arity() != size) {
            throw new Error(Tuple.of(Atom.of("badrecord"), source));
        }

        Tuple result = new Tuple(source.toTuple());
        while (updates.length() > 0) {
            if (updates.length() < 2) {
                throw Error.badarg;
            }
            int index = updates.head().toArg(proc).toInteger().toInt();
            Term value = updates.tail().head().toArg(proc);
            if (index < 1 || index > size) {
                throw new Error(Tuple.of(Atom.of("badrecord"), source));
            }
            result.set(index, value);
            updates = updates.tail().tail();
        }

        if (destination.isXRegister()) {
            proc.setX(destination.toRegisterIndex(), result);
        } else if (destination.isYRegister()) {
            proc.setY(destination.toRegisterIndex(), result);
        } else {
            throw new Error("Unsupported destination: " + destination);
        }
        return null;
    }

}
