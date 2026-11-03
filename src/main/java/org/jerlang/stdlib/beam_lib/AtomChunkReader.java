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
        // The count sizes an array before a single atom has been read, so it
        // has to be believed only as far as the file can back it. Every atom
        // costs at least its length byte, which makes the bytes left in the
        // file an upper bound. Math.abs leaves Integer.MIN_VALUE negative, and
        // that is refused by the same test.
        if (numberOfAtoms < 0 || numberOfAtoms > inputStream().available()) {
            throw new IllegalArgumentException("atom count " + count
                + " exceeds the " + inputStream().available() + " bytes left in the file");
        }
        AtomChunk atomChunk = new AtomChunk(chunk(), numberOfAtoms);
        for (int index = 0; index < numberOfAtoms; index++) {
            int length = compactLengths ? decodeArg(null, null).toInteger().toInt() : read1Byte();
            // A length read out of the file is not evidence that the bytes
            // exist. A mutated chunk claimed two gigabytes for a one byte atom,
            // and allocating before checking turned a corrupt file into an
            // OutOfMemoryError. Every atom byte costs at least one input byte,
            // so what is left in the file is the ceiling.
            if (length < 0 || length > inputStream().available()) {
                throw new IllegalArgumentException("atom length " + length
                    + " exceeds the " + inputStream().available() + " bytes left in the file");
            }
            byte[] bytes = new byte[length];
            readBytes(bytes);
            atomChunk.set(index, utf8
                ? Atom.of(new String(bytes, StandardCharsets.UTF_8))
                : Atom.of(bytes));
        }
        // The table ends where the count says it ends. The reader used to read
        // one more byte here, which never failed because the loader hands it a
        // stream over the whole file rather than over the chunk: the byte it
        // consumed belonged to the next chunk id. Assembling needs the real
        // length, so the read is gone and the chunk ends with the last atom.
        return atomChunk;
    }

}
