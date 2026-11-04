package org.jerlang.stdlib.beam_lib;

import java.io.ByteArrayOutputStream;
import java.util.List;

import org.jerlang.erts.etf.ExternalTermWriter;
import org.jerlang.type.Term;

/**
 * = Writing a literal table
 *
 * The compiler emits a literal index in an instruction when a term can not be
 * represented as an operand, for example the empty flag list in a binary match
 * command. The literal itself lives in the {@code LitT} chunk, encoded in the
 * external term format.
 *
 * OTP 28 and later accept an uncompressed table: a zero size word, the number
 * of literals, then one length-prefixed external term per literal. That is the
 * format written here. The older zlib-compressed form is still read by
 * {@link LiteralTableChunkReader}, but the compiler has no reason to compress a
 * table it is about to load from the same file.
 */
public final class LiteralTableChunkWriter {

    private LiteralTableChunkWriter() {
    }

    public static byte[] write(List<Term> literals) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        BeamBytes.write4(out, 0);
        BeamBytes.write4(out, literals.size());
        for (Term literal : literals) {
            ExternalTermWriter writer = new ExternalTermWriter();
            // Literal records carry the external term format version byte,
            // unlike the terms nested inside a tuple or list.
            writer.write(literal);
            byte[] encoded = writer.toByteArray();
            BeamBytes.write4(out, encoded.length);
            out.write(encoded, 0, encoded.length);
        }
        return out.toByteArray();
    }

}
