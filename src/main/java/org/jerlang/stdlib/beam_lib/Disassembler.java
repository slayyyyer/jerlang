package org.jerlang.stdlib.beam_lib;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;

import org.jerlang.type.Atom;

/**
 * = The Disassembler
 *
 * Turns a BEAM file into a line-oriented text form. The form exists to be read
 * and diffed: a codegen bug shows up as a change in a listing instead of a
 * digest that moved, and a listing that is checked in is a regression test for
 * every chunk at once.
 *
 * The first slice decodes the container, the atom table and the three function
 * tables. Everything else is written as hex, which is exact and unreadable on
 * purpose: {@link TextAssembler} already round-trips it, so the remaining
 * decoders can replace one section at a time without the oracle changing.
 *
 * The format is line based, because a diff of a line-oriented file is readable
 * and a diff of a nested one is not:
 *
 * [source]
 * ----
 * beam 1
 *
 * chunk AtU8 356
 * count 42
 * atom 1 lists
 * ...
 * end
 *
 * chunk Code 790
 * hex 00000000...
 * end
 * ----
 *
 * The chunk header repeats the payload length so a truncated or edited listing
 * is refused rather than silently written out as a different module.
 */
public final class Disassembler {

    private static final int HEX_BYTES_PER_LINE = 32;

    private Disassembler() {
    }

    public static String disassemble(byte[] beam) {
        BeamFile file = BeamFile.read(beam);
        StringBuilder text = new StringBuilder("beam 1\n");
        for (String id : file.chunkIds()) {
            byte[] payload = file.payload(id);
            text.append("\nchunk ").append(id).append(' ').append(payload.length).append('\n');
            if ("Atom".equals(id) || "AtU8".equals(id)) {
                atoms(text, id, payload);
            } else if ("ExpT".equals(id)) {
                table(text, payload, "export");
            } else if ("ImpT".equals(id)) {
                table(text, payload, "import");
            } else if ("LocT".equals(id)) {
                table(text, payload, "local");
            } else {
                hex(text, payload);
            }
            text.append("end\n");
        }
        return text.toString();
    }

    private static void atoms(StringBuilder text, String id, byte[] payload) {
        try {
            AtomChunk chunk = new AtomChunkReader(new Chunk(ChunkId.of(id), 0, payload.length),
                new DataInputStream(new ByteArrayInputStream(payload)), "AtU8".equals(id)).read();
            // The sign of the count is part of the format, so it is printed as
            // it was read rather than normalised away.
            text.append("count ").append(BeamBytes.read4(payload, 0)).append('\n');
            Atom[] atoms = chunk.atoms();
            for (int index = 0; index < atoms.length; index++) {
                text.append("atom ").append(index + 1).append(' ')
                    .append(escape(atoms[index].toString())).append('\n');
            }
        } catch (Throwable throwable) {
            throw new IllegalStateException("can not disassemble the " + id + " chunk", throwable);
        }
    }

    private static void table(StringBuilder text, byte[] payload, String verb) {
        int count = BeamBytes.read4(payload, 0);
        text.append("count ").append(count).append('\n');
        for (int record = 0; record < count; record++) {
            int offset = 4 + record * 12;
            text.append(verb).append(' ')
                .append(BeamBytes.read4(payload, offset)).append(' ')
                .append(BeamBytes.read4(payload, offset + 4)).append(' ')
                .append(BeamBytes.read4(payload, offset + 8)).append('\n');
        }
    }

    private static void hex(StringBuilder text, byte[] payload) {
        if (payload.length == 0) {
            text.append("hex\n");
            return;
        }
        for (int offset = 0; offset < payload.length; offset += HEX_BYTES_PER_LINE) {
            text.append("hex ");
            int end = Math.min(offset + HEX_BYTES_PER_LINE, payload.length);
            for (int index = offset; index < end; index++) {
                text.append(String.format("%02x", payload[index] & 0xFF));
            }
            text.append('\n');
        }
    }

    /**
     * An atom can contain anything, including spaces and newlines, so the name
     * is escaped before it goes on a line of its own.
     */
    static String escape(String value) {
        StringBuilder escaped = new StringBuilder(value.length());
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            switch (character) {
            case '\\':
                escaped.append("\\\\");
                break;
            case '\n':
                escaped.append("\\n");
                break;
            case '\r':
                escaped.append("\\r");
                break;
            default:
                escaped.append(character);
            }
        }
        return escaped.toString();
    }

}
