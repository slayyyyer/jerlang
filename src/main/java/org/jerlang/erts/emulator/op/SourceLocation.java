package org.jerlang.erts.emulator.op;

import static java.nio.charset.StandardCharsets.UTF_8;

import org.jerlang.Module;
import org.jerlang.Process;
import org.jerlang.stdlib.beam_lib.LineRecord;
import org.jerlang.stdlib.beam_lib.LineTableChunk;
import org.jerlang.type.Atom;
import org.jerlang.type.BitString;
import org.jerlang.type.Integer;
import org.jerlang.type.List;
import org.jerlang.type.Str;
import org.jerlang.type.Term;
import org.jerlang.type.Tuple;
import org.jerlang.type.stack.StackFrame;

/**
 * Updates the current stack frame with a source location.
 *
 * OTP 27 and later pass the location inline as
 * [{location,Filename,Line}] and use executable_line/debug_line for
 * breakpoint places. Older beams pass an index into the line table.
 */
final class SourceLocation {

    private static final Atom LOCATION = Atom.of("location");

    private SourceLocation() {
    }

    static void set(Process process, Module m, Term location) {
        StackFrame frame = process.currentFrame();
        if (frame == null) {
            return;
        }

        if (location instanceof Integer) {
            setFromLineTable(frame, m, location.toInteger().toInt());
            return;
        }

        if (!(location instanceof List)) {
            return;
        }

        List list = location.toList();
        if (list.length() == 0) {
            return;
        }

        Term entry = list.head();
        if (!(entry instanceof Tuple)) {
            return;
        }
        Tuple tuple = entry.toTuple();
        if (tuple.arity() < 3 || !tuple.element(1).equals(LOCATION)) {
            return;
        }

        int line = tuple.element(3).toInteger().toInt();
        if (line > 0) {
            frame.line(fileName(tuple.element(2), m), line);
        }
    }

    private static void setFromLineTable(StackFrame frame, Module m, int index) {
        LineTableChunk lines = m.beamData() == null ? null : m.beamData().lineTableChunk();
        if (lines == null) {
            return;
        }
        LineRecord[] records = lines.records();
        // Line references are 1-based; 0 means "no source location".
        int position = index - 1;
        if (position < 0 || position >= records.length) {
            return;
        }
        LineRecord record = records[position];
        String filename = record.filename().toString();
        if (record.filename().equals(m.name()) && m.sourceName() != null) {
            // The line table has no filename table; use the source name from
            // the compile info chunk instead of the module name.
            filename = m.sourceName().string();
        }
        frame.line(filename, record.lineNumber());
    }

    private static String fileName(Term term, Module m) {
        if (term instanceof Atom && m.sourceName() != null) {
            return m.sourceName().string();
        }
        if (term instanceof BitString) {
            String path = new String(term.toBitString().toByteArray(), UTF_8);
            int separator = Math.max(path.lastIndexOf('/'), path.lastIndexOf('\\'));
            return separator >= 0 ? path.substring(separator + 1) : path;
        }
        if (term instanceof Str) {
            String path = term.toStr().string();
            int separator = Math.max(path.lastIndexOf('/'), path.lastIndexOf('\\'));
            return separator >= 0 ? path.substring(separator + 1) : path;
        }
        return term.toString();
    }

}
