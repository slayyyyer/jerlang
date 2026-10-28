package org.jerlang.erts.emulator.op;

import org.jerlang.Module;
import org.jerlang.Process;
import org.jerlang.erts.emulator.Instruction;
import org.jerlang.type.List;
import org.jerlang.type.Term;

/**
 * Continue a receive from the mailbox position a marker recorded.
 *
 * The marker only changes where the following loop_rec starts scanning; the
 * instruction after recv_marker_use is always the loop it resumes, so
 * falling through is the right behaviour for an interpreter that scans the
 * mailbox from the head.
 *
 * Arguments:
 * 1. Marker reference
 *
 * Example:
 * {recv_marker_use,{y,0}}
 */
public class RecvMarkerUse {

    public static Term execute(Process proc, Module m, Instruction i, List params) {
        return null;
    }

}
