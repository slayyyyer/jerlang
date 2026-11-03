package org.jerlang.stdlib.beam_lib;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

import org.jerlang.type.Atom;

/**
 * Writes the {@code Atom} or {@code AtU8} chunk.
 *
 * OTP 28 changed two things about this chunk and both are part of a module's
 * identity: a negative count means every atom length uses the compact operand
 * encoding instead of one byte, and the atom names are UTF-8. A writer that
 * always used the older form produced a valid module with a different digest,
 * so both are read from the chunk that is being written rather than assumed.
 *
 * The table is exactly the count followed by the atoms. There is no terminator:
 * the reader used to read one byte past the last atom and always succeeded,
 * because the loader gives it a stream over the whole file, so the byte it took
 * was the first byte of the next chunk id.
 */
public final class AtomChunkWriter {

    private AtomChunkWriter() {
    }

    public static byte[] write(AtomChunk chunk, byte[] original) {
        boolean compactLengths = original.length >= 4 && BeamBytes.read4(original, 0) < 0;
        boolean utf8 = chunk.id() == ChunkId.ATU8;
        ByteArrayOutputStream out = new ByteArrayOutputStream(original.length);
        Atom[] atoms = chunk.atoms();
        BeamBytes.write4(out, compactLengths ? -atoms.length : atoms.length);
        for (Atom atom : atoms) {
            byte[] bytes = atom.toString().getBytes(utf8
                ? StandardCharsets.UTF_8
                : StandardCharsets.ISO_8859_1);
            if (compactLengths) {
                BeamBytes.writeCompact(out, bytes.length, 0);
            } else {
                out.write(bytes.length);
            }
            out.write(bytes, 0, bytes.length);
        }
        return out.toByteArray();
    }

}
