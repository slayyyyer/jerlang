package org.jerlang.erts.emulator.op;

import org.jerlang.Module;
import org.jerlang.Process;
import org.jerlang.erts.emulator.Instruction;
import org.jerlang.type.List;
import org.jerlang.type.Term;

/**
 * Drop the marker bound to a receive reference, so a later receive does not
 * resume from it.
 *
 * This interpreter keeps no mailbox positions, so there is nothing to clear.
 *
 * Arguments:
 * 1. Marker reference
 *
 * Example:
 * {recv_marker_clear,{y,0}}
 */
public class RecvMarkerClear {

    public static Term execute(Process proc, Module m, Instruction i, List params) {
        return null;
    }

}
