package org.jerlang.erts.emulator.op;

import org.jerlang.Module;
import org.jerlang.Process;
import org.jerlang.erts.emulator.Instruction;
import org.jerlang.type.List;
import org.jerlang.type.Term;

/**
 * Create or update a native record. Native records are an experimental OTP 29
 * data type that this runtime does not implement, so construction fails with
 * a named error instead of dereferencing a type that can never exist here.
 *
 * Example:
 * {put_record,{f,0},{atom,id},{atom,nil},{x,0},2,{list,[...]}}
 */
public class PutRecord {

    public static Term execute(Process proc, Module m, Instruction i, List params) {
        throw NativeRecords.unsupported();
    }

}
