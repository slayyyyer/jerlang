package org.jerlang.erts.emulator.op;

import org.jerlang.FunctionSignature;
import org.jerlang.Module;
import org.jerlang.Process;
import org.jerlang.ProcessState;
import org.jerlang.erts.Erlang;
import org.jerlang.erts.emulator.Instruction;
import org.jerlang.exception.ThrowException;
import org.jerlang.type.Integer;
import org.jerlang.type.List;
import org.jerlang.type.Term;

/**
 * Call the function of arity Arity pointed to by Destination.
 * Save the next instruction as the return address in the CP register.
 *
 * First argument is the arity.
 * Second argument is the index of the function in import chunk.
 */
public class CallExt {

    public static Term execute(Process proc, Module m, Instruction i, List params) throws ThrowException {
        return execute(proc, m, i, params, true);
    }

    /**
     * @param resume whether this activation continues after the call. A tail
     * call replaces it, so there is no continuation to remember.
     */
    public static Term execute(Process proc, Module m, Instruction i, List params, boolean resume)
        throws ThrowException {
        Integer arity = i.arg(0).toInteger();
        List parameters = parameters(proc, arity);
        Integer exportTableIndex = i.arg(1).toInteger();
        FunctionSignature s = resolve(m, exportTableIndex);
        // The callee runs its own instruction stream on the Java stack, so
        // record where this one continues in case the process parks inside
        // the call: a parked process loses those Java frames.
        if (resume) {
            proc.pushResumePoint(m, i.index() + 1);
        }
        Term result;
        try {
            result = Erlang.apply(s.module(), s.function(), parameters);
        } finally {
            // A parked process keeps the point: it is how the activation is
            // re-entered when the process wakes up.
            if (resume && proc.state() != ProcessState.WAITING) {
                proc.popResumePoint();
            }
        }
        proc.setX(0, result);
        return null;
    }

    static List parameters(Process proc, Integer arity) {
        List params = List.nil;
        int maxIndex = arity.toInt();
        for (int index = maxIndex - 1; index >= 0; index--) {
            params = new List(proc.registers()[index], params);
        }
        return params;
    }

    static FunctionSignature resolve(Module m, Integer index) {
        return m.beamData().importTableChunk().imports().get(index.toInt());
    }

}
