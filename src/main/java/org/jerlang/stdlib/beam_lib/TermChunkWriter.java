package org.jerlang.stdlib.beam_lib;

/**
 * The literal table, and why the term chunks are not re-encoded in A1.
 *
 * A1.5 asked for byte identity "or a written reason a term can not round trip".
 * There are three reasons, and all three were found by running the writers over
 * the corpus rather than predicted:
 *
 * . The external term format has more than one encoding for the same term. The
 *   compiler writes {@code ATOM_EXT} with a two byte length; {@code
 *   ExternalTermWriter} writes {@code SMALL_ATOM_UTF8_EXT} with a one byte
 *   length. A 40 byte {@code Attr} chunk came back as 39 bytes.
 * . A map's pairs are ordered on the wire and the runtime's {@code Map} does not
 *   preserve that order. A literal {@code #{a := 10, b := 2}} came back as
 *   {@code #{b := 2, a := 10}}: the same term, different bytes.
 * . Before OTP 28 the table is a zlib stream, and inflating then deflating it
 *   again gives a valid stream that is not the same stream.
 *
 * None of this is a problem for the assembler, because every one of these
 * chunks can be carried unchanged. It is a problem for the compiler in A5,
 * which has to *choose* an encoding and then be consistent about it; that is
 * where the writer gets built, together with a map representation that keeps
 * insertion order. {@code LitT} is part of {@code beam_lib:md5/1}, so until
 * then it is copied: a re-compressed or re-ordered table would change the
 * module's identity while changing nothing about the code.
 */
public final class TermChunkWriter {

    private TermChunkWriter() {
    }

    /**
     * The literal table as it was read, for the reasons in the class comment.
     */
    public static byte[] literals(LiteralTableChunk chunk, byte[] original) {
        return original.clone();
    }

}
