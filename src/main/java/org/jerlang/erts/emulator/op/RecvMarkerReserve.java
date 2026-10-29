package org.jerlang.erts.emulator.op;

import org.jerlang.Module;
import org.jerlang.Process;
import org.jerlang.erts.emulator.Instruction;
import org.jerlang.type.List;
import org.jerlang.type.Reference;
import org.jerlang.type.Term;

/**
 * Allocate a receive marker and store it in Destination.
 *
 * OTP 28 uses markers to let the next receive in the same function continue
 * scanning the mailbox where the previous one stopped. This interpreter
 * always scans from the head of the mailbox, so the marker only carries the
 * reference that recv_marker_bind and recv_marker_use name it by.
 *
 * Arguments:
 * 1. Destination
 *
 * Example:
 * {recv_marker_reserve,{y,2}}
 */
public class RecvMarkerReserve {

    public static Term execute(Process proc, Module m, Instruction i, List params) {
        Term destination = i.arg(0);
        Term marker = new Reference();
        if (destination.isXRegister()) {
            proc.setX(destination.toRegisterIndex(), marker);
        } else if (destination.isYRegister()) {
            proc.setY(destination.toRegisterIndex(), marker);
        } else {
            throw new Error("Unsupported destination: " + destination);
        }
        return null;
    }

}
