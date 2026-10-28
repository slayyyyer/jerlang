package org.jerlang.erts.emulator.op;

import org.jerlang.Module;
import org.jerlang.Process;
import org.jerlang.erts.emulator.Instruction;
import org.jerlang.stdlib.beam_lib.LineRecord;
import org.jerlang.stdlib.beam_lib.LineTableChunk;
import org.jerlang.type.Integer;
import org.jerlang.type.List;
import org.jerlang.type.Term;
import org.jerlang.type.stack.StackFrame;

/**
 * Set the current source location of the running function.
 *
 * The operand is an index into the module's line table.
 */
public class Line {

    public static Term execute(Process process, Module m, Instruction i, List params) {
        Term arg = i.arg(0);
        if (!(arg instanceof Integer)) {
            return null;
        }
        LineTableChunk lines = m.beamData().lineTableChunk();
        if (lines == null) {
            return null;
        }
        LineRecord[] records = lines.records();
        // Line references are 1-based; 0 means "no source location".
        int index = arg.toInteger().toInt() - 1;
        if (index < 0 || index >= records.length) {
            return null;
        }
        LineRecord record = records[index];
        String filename = record.filename().toString();
        if (record.filename().equals(m.name()) && m.sourceName() != null) {
            // The line table has no filename table; use the source name from
            // the compile info chunk instead of the module name.
            filename = m.sourceName().string();
        }
        StackFrame frame = process.currentFrame();
        if (frame != null) {
            frame.line(filename, record.lineNumber());
        }
        return null;
    }

}
