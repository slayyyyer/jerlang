package org.jerlang.erts.emulator.op;

import org.jerlang.Module;
import org.jerlang.Process;
import org.jerlang.erts.emulator.Instruction;
import org.jerlang.erts.erlang.Error;
import org.jerlang.type.Atom;
import org.jerlang.type.List;
import org.jerlang.type.Term;
import org.jerlang.type.stack.BinMatchState;

/**
 * Start a binary match with the failure mode folded into the instruction.
 *
 * `{f,Label}` fails like bs_start_match3. `no_fail` is used when the compiler
 * knows the source is matchable, and `resume` when it is already a match
 * context that should be continued.
 *
 * Arguments:
 * 1. Fail mode: a label, `no_fail` or `resume`
 * 2. Live registers
 * 3. Source
 * 4. Destination
 *
 * Example:
 * {bs_start_match4,{atom,no_fail},1,{x,0},{x,1}}
 */
public class BsStartMatch4 {

    private static final Atom NO_FAIL = Atom.of("no_fail");
    private static final Atom RESUME = Atom.of("resume");

    public static Term execute(Process proc, Module m, Instruction i, List params) {
        Term mode = i.arg(0);
        Term source = i.arg(2).toArg(proc);
        Term destination = i.arg(3);

        if (RESUME.equals(mode)) {
            if (!(source instanceof BinMatchState)) {
                throw new Error("bs_start_match4 resume on " + source);
            }
            BsStartMatch3.write(proc, destination, new BinMatchState((BinMatchState) source));
            return null;
        }

        BinMatchState context = BsStartMatch3.start(source);
        if (context != null) {
            BsStartMatch3.write(proc, destination, context);
            return null;
        }

        if (NO_FAIL.equals(mode)) {
            throw new Error("bs_start_match4 source is not matchable: " + source);
        }
        return mode;
    }

}
