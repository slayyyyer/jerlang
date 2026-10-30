package org.jerlang.erts.emulator.op;

import org.jerlang.Module;
import org.jerlang.Process;
import org.jerlang.erts.emulator.Instruction;
import org.jerlang.type.Atom;
import org.jerlang.type.List;
import org.jerlang.type.Term;

/**
 * Suspend in the receive loop at Label, but wake up after Timeout
 * milliseconds if no matching message arrives first. The continuation
 * pointer saved by the interpreter points at this instruction, so a timeout
 * resumes with the following `timeout` instruction.
 *
 * Arguments:
 * 1. Label of the receive loop
 * 2. Timeout in milliseconds, the atom infinity, or a register holding one
 *    of the two
 *
 * Example:
 * {wait_timeout,{f,59},{y,2}}
 */
public class WaitTimeout {

    private static final Atom INFINITY = Atom.of("infinity");

    public static Term execute(Process proc, Module m, Instruction i, List params) {
        Term label = i.arg(0);
        // The compiler inlines a literal timeout but passes a variable one in
        // a register, which is how proc_lib:start/3 forwards its default of
        // infinity.
        Term timeoutTerm = i.arg(1).toArg(proc);
        long timeout;
        if (INFINITY.equals(timeoutTerm)) {
            timeout = -1;
        } else {
            timeout = timeoutTerm.toInteger().toLong();
        }
        proc.waitForMessage(m, i.index(), label, timeout);
        return null;
    }

}
