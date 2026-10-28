package org.jerlang.stdlib.beam_lib;

import java.io.DataInputStream;
import java.nio.charset.StandardCharsets;

import org.jerlang.type.Atom;

public class AtomChunkReader extends AbstractChunkReader<AtomChunk> {

    private final boolean utf8;

    public AtomChunkReader(Chunk chunk, DataInputStream inputStream) {
        this(chunk, inputStream, false);
    }

    public AtomChunkReader(Chunk chunk, DataInputStream inputStream, boolean utf8) {
        super(chunk, inputStream);
        this.utf8 = utf8;
    }

    public AtomChunk read() throws Throwable {
        // OTP 28 writes a negative atom count to signal that each atom length
        // is encoded with the compact instruction encoding instead of a byte.
        int count = read4Bytes();
        boolean compactLengths = count < 0;
        int numberOfAtoms = Math.abs(count);
        AtomChunk atomChunk = new AtomChunk(chunk(), numberOfAtoms);
        for (int index = 0; index < numberOfAtoms; index++) {
            int length = compactLengths ? decodeArg(null, null).toInteger().toInt() : read1Byte();
            byte[] bytes = new byte[length];
            readBytes(bytes);
            atomChunk.set(index, utf8
                ? Atom.of(new String(bytes, StandardCharsets.UTF_8))
                : Atom.of(bytes));
        }
        read1Byte(); // null byte
        return atomChunk;
    }

}
