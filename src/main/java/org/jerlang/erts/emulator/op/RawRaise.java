package org.jerlang.erts.emulator.op;

import org.jerlang.Module;
import org.jerlang.Process;
import org.jerlang.erts.emulator.Instruction;
import org.jerlang.erts.erlang.Error;
import org.jerlang.type.List;
import org.jerlang.type.Term;

/**
 * Raise the exception in X0 (class), X1 (reason) and X2 (stacktrace),
 * leaving the stacktrace as it is.
 *
 * Introduced in OTP 24 for `erlang:raise/3` and for re-raising a caught
 * exception. Unlike `raise`, it does not build a new stacktrace.
 *
 * Example:
 * raw_raise
 */
public class RawRaise {

    public static Term execute(Process proc, Module m, Instruction i, List params) {
        throw new Error(
            proc.getX(0).toAtom(),
            proc.getX(1),
            proc.getX(2));
    }

}
