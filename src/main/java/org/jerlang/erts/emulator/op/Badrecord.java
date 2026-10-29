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
 * Raises a {badrecord,Value} error exception.
 *
 * Example:
 * {badrecord,{x,0}}
 */
public class Badrecord {

    public static Term execute(Process proc, Module m, Instruction i, List params) {
        throw new Error(Tuple.of(Atom.of("badrecord"), i.arg(0).toArg(proc)));
    }

}
