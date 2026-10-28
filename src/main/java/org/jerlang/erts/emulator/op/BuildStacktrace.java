package org.jerlang.erts.emulator.op;

import org.jerlang.Module;
import org.jerlang.Process;
import org.jerlang.erts.emulator.Instruction;
import org.jerlang.type.List;
import org.jerlang.type.Term;

/**
 * Turn the raw stacktrace in X0 into a list of stack frames, in place.
 *
 * JErlang records `{Module, Function, Arity, Location}` frames as functions
 * are called, so the value in X0 is already a frame list and only needs
 * normalising.
 *
 * Example:
 * build_stacktrace
 */
public class BuildStacktrace {

    public static Term execute(Process proc, Module m, Instruction i, List params) {
        Term raw = proc.getX(0);
        if (!(raw instanceof List)) {
            proc.setX(0, List.nil);
        }
        return null;
    }

}
