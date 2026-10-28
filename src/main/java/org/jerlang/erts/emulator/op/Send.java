package org.jerlang.erts.emulator.op;

import org.jerlang.Module;
import org.jerlang.Process;
import org.jerlang.erts.dist.Distribution;
import org.jerlang.erts.emulator.Instruction;
import org.jerlang.type.List;
import org.jerlang.type.Term;

/**
 * Send argument in x(1) as a message to the destination in x(0).
 * The destination may be a PID, a registered name, or a {Name, Node}
 * tuple. The message in x(1) ends up as the result of the send in x(0).
 */
public class Send {

    public static Term execute(Process proc, Module m, Instruction i, List params) {
        Term target = proc.getX(0);
        Term message = proc.getX(1);
        Distribution.send(target, proc.pid(), message);
        proc.setX(0, message);
        return null;
    }

}
