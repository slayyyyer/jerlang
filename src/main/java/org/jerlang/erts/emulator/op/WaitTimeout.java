package org.jerlang.erts.emulator.op;

import org.jerlang.Module;
import org.jerlang.Process;
import org.jerlang.erts.emulator.Instruction;
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
 * 2. Timeout in milliseconds, or a negative value for infinity
 */
public class WaitTimeout {

    public static Term execute(Process proc, Module m, Instruction i, List params) {
        Term label = i.arg(0);
        long timeout = i.arg(1).toInteger().toLong();
        proc.waitForMessage(label, timeout);
        return null;
    }

}
