package org.jerlang.stdlib.beam_lib;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.jerlang.stdlib.beam_lib.BeamLibMD5;
import org.jerlang.type.Str;
import org.jerlang.type.Term;
import org.junit.Test;

/**
 * A1.9: the corpus gate.
 *
 * The exit test for the assembler is {@code beam_lib:md5/1} identity over every
 * checked-in fixture. The digest is computed by the production implementation
 * rather than by the test, because the digest is the thing a peer compares: a
 * test that agreed with a wrong implementation would gate nothing.
 *
 * Loading the re-emitted files on OTP 29 is part of the milestone and is not
 * run here: there is no Erlang installation on this machine. It stays an open
 * item until a machine with OTP runs it.
 */
public class BeamAssemblerTest {

    @Test
    public void md5IsUnchangedForEveryFixture() throws Exception {
        List<Path> fixtures = Fixtures.paths();
        assertTrue("expected the checked-in fixtures, found " + fixtures.size(),
            fixtures.size() >= 100);
        for (Path path : fixtures) {
            byte[] original = Fixtures.bytes(path);
            byte[] assembled = BeamAssembler.assemble(original);
            assertEquals(path.toString(), md5(original), md5(assembled));
        }
    }

    @Test
    public void chunkOrderIsUnchangedForEveryFixture() throws Exception {
        for (Path path : Fixtures.paths()) {
            byte[] original = Fixtures.bytes(path);
            byte[] assembled = BeamAssembler.assemble(original);
            assertEquals(path.toString(), BeamFile.read(original).chunkIds(),
                BeamFile.read(assembled).chunkIds());
        }
    }

    /**
     * The chunks nobody writes are still carried: a file with a metadata chunk
     * the loader never interprets must come out with that chunk intact, or the
     * assembler loses data whenever a newer compiler writes one.
     */
    @Test
    public void uninterpretedChunksSurviveAssembly() {
        byte[] file = beam(
            chunk("Atom", new byte[] { 0, 0, 0, 0 }),
            chunk("Dbgi", new byte[] { 1, 2, 3, 4, 5, 6 }),
            chunk("Meta", new byte[] { 9 }));
        byte[] assembled = BeamAssembler.assemble(file);
        assertArrayEquals(file, assembled);
    }

    private static String md5(byte[] bytes) throws Exception {
        Path temp = Files.createTempFile("jerlang-a1", ".beam");
        try {
            Files.write(temp, bytes);
            Term digest = BeamLibMD5.md5_1(Str.of(temp.toString()));
            return digest.toString();
        } finally {
            Files.deleteIfExists(temp);
        }
    }

    private static byte[] beam(byte[]... chunks) {
        int body = 0;
        for (byte[] chunk : chunks) {
            body += chunk.length;
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream(12 + body);
        write4(out, 0x464f5231); // FOR1
        write4(out, 4 + body);
        write4(out, 0x4245414d); // BEAM
        for (byte[] chunk : chunks) {
            out.write(chunk, 0, chunk.length);
        }
        return out.toByteArray();
    }

    private static byte[] chunk(String id, byte[] payload) {
        ByteArrayOutputStream out = new ByteArrayOutputStream(8 + payload.length + 3);
        out.write(id.getBytes(StandardCharsets.ISO_8859_1), 0, 4);
        write4(out, payload.length);
        out.write(payload, 0, payload.length);
        for (int pad = payload.length; pad % 4 != 0; pad++) {
            out.write(0);
        }
        return out.toByteArray();
    }

    private static void write4(ByteArrayOutputStream out, int value) {
        out.write((value >>> 24) & 0xFF);
        out.write((value >>> 16) & 0xFF);
        out.write((value >>> 8) & 0xFF);
        out.write(value & 0xFF);
    }

}
