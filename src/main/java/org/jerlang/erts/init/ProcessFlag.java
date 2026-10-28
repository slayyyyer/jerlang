package org.jerlang.erts.init;

import org.jerlang.Process;
import org.jerlang.type.Atom;

public class ProcessFlag {

    private ProcessFlag() {
    }

    /**
     * Set a process flag. Returns the previous value, which is what
     * process_flag/2 returns to Erlang code.
     */
    public static boolean process_flag(Process process, Atom flag, boolean value) {
        switch (flag.toString()) {
        case "trap_exit":
            return process.setTrapExit(value);
        default:
            return false;
        }
    }

}
