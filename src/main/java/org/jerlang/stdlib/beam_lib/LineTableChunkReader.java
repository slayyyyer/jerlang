package org.jerlang.stdlib.beam_lib;

import java.io.DataInputStream;
import java.nio.charset.StandardCharsets;

import org.jerlang.OpcodeTag;
import org.jerlang.type.Atom;
import org.jerlang.type.Str;

public class LineTableChunkReader extends AbstractChunkReader<LineTableChunk> {

    private final AtomChunk atomChunk;

    public LineTableChunkReader(Chunk chunk, DataInputStream inputStream, AtomChunk atomChunk) {
        super(chunk, inputStream);
        this.atomChunk = atomChunk;
    }

    public LineTableChunk read() throws Throwable {
        LineTableChunk lineTableChunk = new LineTableChunk(chunk(),
            read4Bytes(), read4Bytes(), read4Bytes(), read4Bytes(), read4Bytes());

        // From beam_asm.erl:
        // "Encode the line items compactly.
        // Tag the FnameIndex with an 'a' tag (atom) and
        // only include it when it has changed.
        // Tag the line numbers with an 'i' (integer) tag."
        //
        // The 'a' tag carries an index into this chunk's own filename table,
        // not an index into the atom chunk.
        int[] filenameIndexes = new int[lineTableChunk.records().length];
        int[] lineNumbers = new int[lineTableChunk.records().length];
        int filenameIndex = 0;

        for (int index = 0; index < lineTableChunk.records().length; index++) {
            int b = read1Byte();
            if (OpcodeTag.decode(b) == OpcodeTag.a) {
                filenameIndex = decodeInt(b).toInt();
                b = read1Byte();
            }
            filenameIndexes[index] = filenameIndex;
            lineNumbers[index] = decodeInt(b).toInt();
        }

        Str[] fnames = lineTableChunk.fnames();
        for (int index = 0; index < fnames.length; index++) {
            int len = read2Bytes();
            byte[] bytes = new byte[len];
            readBytes(bytes);
            fnames[index] = new Str(new String(bytes, StandardCharsets.UTF_8));
        }

        Atom module = atomChunk == null ? Atom.of("?") : atomChunk.atoms()[0];
        for (int index = 0; index < lineTableChunk.records().length; index++) {
            int fname = filenameIndexes[index];
            Atom filename = fname < fnames.length && fnames[fname] != null
                ? Atom.of(fnames[fname].string())
                : module;
            lineTableChunk.records()[index] = new LineRecord(filename, lineNumbers[index]);
        }

        return lineTableChunk;
    }

}
