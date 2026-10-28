package org.jerlang.erts.emulator.op;

import org.jerlang.Module;
import org.jerlang.Process;
import org.jerlang.erts.emulator.Instruction;
import org.jerlang.type.List;
import org.jerlang.type.Term;

/**
 * Bind a receive marker to the reference of the receive it belongs to.
 *
 * The reference is what recv_marker_use and recv_marker_clear name the
 * marker by. Since this interpreter scans the mailbox from the head, there
 * is no scan position to remember and the binding has no effect.
 *
 * Arguments:
 * 1. Marker
 * 2. Reference
 *
 * Example:
 * {recv_marker_bind,{y,2},{x,0}}
 */
public class RecvMarkerBind {

    public static Term execute(Process proc, Module m, Instruction i, List params) {
        return null;
    }

}
