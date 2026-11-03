package org.jerlang.stdlib.beam_lib;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

import org.jerlang.OpcodeTag;
import org.jerlang.type.Str;

/**
 * Writes the {@code Line} chunk.
 *
 * The records are encoded the way {@code beam_asm.erl} encodes them: the file
 * name index is only written when it changes, tagged with {@code a} and
 * indexing this chunk's own filename table, and every record carries its line
 * number tagged with {@code i}. A reader that resolved the filename to an atom
 * and then wrote it back on every record would produce a different, longer
 * chunk, so the "was it written" bit is kept in the record.
 */
public final class LineTableChunkWriter {

    private LineTableChunkWriter() {
    }

    public static byte[] write(LineTableChunk chunk) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        BeamBytes.write4(out, chunk.version());
        BeamBytes.write4(out, chunk.flags());
        BeamBytes.write4(out, chunk.count());
        BeamBytes.write4(out, chunk.records().length);
        BeamBytes.write4(out, chunk.fnames().length);
        for (LineRecord record : chunk.records()) {
            if (record.explicitFilename()) {
                BeamBytes.writeCompact(out, record.filenameIndex(), OpcodeTag.a.ordinal());
            }
            BeamBytes.writeCompact(out, record.lineNumber(), OpcodeTag.i.ordinal());
        }
        for (Str filename : chunk.fnames()) {
            byte[] bytes = filename.string().getBytes(StandardCharsets.UTF_8);
            out.write((bytes.length >>> 8) & 0xFF);
            out.write(bytes.length & 0xFF);
            out.write(bytes, 0, bytes.length);
        }
        return out.toByteArray();
    }

}
