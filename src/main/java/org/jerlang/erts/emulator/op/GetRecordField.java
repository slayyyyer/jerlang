package org.jerlang.erts.emulator.op;

import org.jerlang.Module;
import org.jerlang.Process;
import org.jerlang.erts.emulator.Instruction;
import org.jerlang.erts.erlang.Error;
import org.jerlang.type.List;
import org.jerlang.type.Term;

/**
 * Get a single field from a native record. This runtime has no native record
 * values; when no fail label is given the access raises badarg, otherwise it
 * jumps to the label.
 *
 * Example:
 * {get_record_field,{f,3},{x,0},{atom,id},{atom,a},{x,1}}
 */
public class GetRecordField {

    public static Term execute(Process proc, Module m, Instruction i, List params) {
        Term fail = i.arg(0);
        // A label is encoded as {f,Number}; {f,0} means "no label" and the
        // access raises instead of jumping.
        if (fail instanceof org.jerlang.type.Tuple) {
            org.jerlang.type.Tuple label = fail.toTuple();
            if (label.arity() == 2 && label.element(2).toInteger().toInt() == 0) {
                throw Error.badarg;
            }
        }
        return fail;
    }

}
