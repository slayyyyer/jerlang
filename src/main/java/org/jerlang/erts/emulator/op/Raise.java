package org.jerlang.erts.emulator.op;

import org.jerlang.Module;
import org.jerlang.Process;
import org.jerlang.erts.emulator.Instruction;
import org.jerlang.erts.erlang.Error;
import org.jerlang.type.Atom;
import org.jerlang.type.List;
import org.jerlang.type.Term;

/**
 * Raise the exception whose class is in X0, with the given stacktrace and
 * reason.
 *
 * The compiler emits this at the end of a `try` whose catch clauses did not
 * match any class, so that the original exception is re-raised unchanged.
 *
 * Arguments:
 * 1. Stacktrace
 * 2. Reason
 *
 * Example:
 * {raise,{x,2},{x,1}}
 */
public class Raise {

    public static Term execute(Process proc, Module m, Instruction i, List params) {
        Term stacktrace = i.arg(0).toArg(proc);
        Term reason = i.arg(1).toArg(proc);
        Term exceptionClass = proc.getX(0);
        if (!(exceptionClass instanceof Atom)) {
            throw Error.badarg;
        }
        throw new Error(exceptionClass.toAtom(), reason, stacktrace);
    }

}
