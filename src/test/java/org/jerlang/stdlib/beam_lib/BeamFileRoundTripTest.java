package org.jerlang.stdlib.beam_lib;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.jerlang.util.ByteUtil;
import org.junit.Test;

/**
 * A1.1: the container.
 *
 * The exit test is byte identity rather than "the file still loads". A file
 * with a dropped or reordered chunk still loads, but it is a different module
 * to every peer that compares {@code beam_lib:md5/1}, because {@code Meta} is
 * part of that digest when it is present.
 */
public class BeamFileRoundTripTest {

    @Test
    public void everyFixtureRoundTripsByteForByte() throws IOException {
        List<Path> fixtures = fixtures();
        assertTrue("expected the checked-in BEAM fixtures, found " + fixtures.size(),
            fixtures.size() >= 100);
        for (Path fixture : fixtures) {
            byte[] original = ByteUtil.maybe_decompress(Files.readAllBytes(fixture));
            byte[] emitted = BeamFile.read(original).toBytes();
            assertArrayEquals(fixture.toString(), original, emitted);
        }
    }

    /**
     * The writer must not be selective. The loader drops {@code Dbgi},
     * {@code Meta}, {@code Type} and the HiPE chunks, so a container built from
     * what the loader understood would lose them; this compares the reader
     * against an independent scan of the same bytes, whatever the fixtures
     * happen to contain.
     */
    @Test
    public void noChunkIsDropped() throws IOException {
        for (Path fixture : fixtures()) {
            byte[] bytes = ByteUtil.maybe_decompress(Files.readAllBytes(fixture));
            assertEquals(fixture.toString(), scanChunkIds(bytes), BeamFile.read(bytes).chunkIds());
        }
    }

    @Test
    public void unknownAndSkippedChunksSurvive() {
        byte[] file = beam(
            chunk("Atom", new byte[] { 0, 0, 0, 0 }),
            chunk("Dbgi", new byte[] { 1, 2, 3, 4, 5, 6 }),
            chunk("Meta", new byte[] { 9 }));
        BeamFile beamFile = BeamFile.read(file);
        assertEquals(Arrays.asList("Atom", "Dbgi", "Meta"), beamFile.chunkIds());
        assertArrayEquals(file, beamFile.toBytes());
    }

    @Test
    public void replacingAPayloadKeepsTheChunkInPlace() {
        byte[] file = beam(
            chunk("Atom", new byte[] { 0, 0, 0, 0 }),
            chunk("LitT", new byte[] { 1, 2, 3 }),
            chunk("Code", new byte[] { 4, 5, 6, 7 }));
        BeamFile beamFile = BeamFile.read(file).replace("LitT", new byte[] { 8, 9 });

        assertEquals(Arrays.asList("Atom", "LitT", "Code"), beamFile.chunkIds());
        assertArrayEquals(new byte[] { 8, 9 }, beamFile.payload("LitT"));
        byte[] emitted = beamFile.toBytes();
        assertArrayEquals(emitted, BeamFile.read(emitted).toBytes());
        // The rewritten container is still readable and keeps the new length.
        assertEquals(Arrays.asList("Atom", "LitT", "Code"),
            BeamFile.read(beamFile.toBytes()).chunkIds());
    }

    @Test(expected = IllegalArgumentException.class)
    public void aChunkThatRunsPastTheEndIsRejected() {
        BeamFile.read(beam(new byte[] {
            'A', 't', 'o', 'm', 0, 0, 0, 10, 1, 2, 3, 4 }));
    }

    @Test(expected = IllegalArgumentException.class)
    public void aFileWithoutTheFor1HeaderIsRejected() {
        BeamFile.read(new byte[] { 'N', 'O', 'P', 'E', 0, 0, 0, 12, 'B', 'E', 'A', 'M' });
    }

    @Test(expected = IllegalArgumentException.class)
    public void aCompressedFileIsRejectedInsteadOfMisread() {
        BeamFile.read(new byte[] { 0x1f, (byte) 0x8b, 8, 0, 0, 0, 0, 0, 0, 0, 0, 0 });
    }

    private static List<Path> fixtures() throws IOException {
        Path root = Paths.get("src/test/resources");
        try (Stream<Path> paths = Files.walk(root)) {
            return paths
                .filter(Files::isRegularFile)
                .filter(path -> path.toString().endsWith(".beam"))
                .sorted()
                .collect(Collectors.toList());
        }
    }

    /**
     * A deliberately separate implementation of the chunk scan, so that a bug
     * shared with {@link BeamFile} can not make the comparison pass.
     */
    private static List<String> scanChunkIds(byte[] bytes) {
        List<String> ids = new ArrayList<>();
        int end = 8 + read4Bytes(bytes, 4);
        int offset = 12;
        while (offset + 8 <= end) {
            String id = new String(bytes, offset, 4, StandardCharsets.ISO_8859_1);
            int size = read4Bytes(bytes, offset + 4);
            ids.add(id);
            offset += 8 + ((size + 3) & ~3);
        }
        return ids;
    }

    private static byte[] beam(byte[]... chunks) {
        int body = 0;
        for (byte[] chunk : chunks) {
            body += chunk.length;
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream(12 + body);
        write4Bytes(out, 0x464f5231); // FOR1
        write4Bytes(out, 4 + body);
        write4Bytes(out, 0x4245414d); // BEAM
        for (byte[] chunk : chunks) {
            out.write(chunk, 0, chunk.length);
        }
        return out.toByteArray();
    }

    private static byte[] chunk(String id, byte[] payload) {
        ByteArrayOutputStream out = new ByteArrayOutputStream(8 + payload.length + 3);
        out.write(id.getBytes(StandardCharsets.ISO_8859_1), 0, 4);
        write4Bytes(out, payload.length);
        out.write(payload, 0, payload.length);
        for (int pad = payload.length; pad % 4 != 0; pad++) {
            out.write(0);
        }
        return out.toByteArray();
    }

    private static int read4Bytes(byte[] bytes, int offset) {
        return ((bytes[offset] & 0xFF) << 24)
            | ((bytes[offset + 1] & 0xFF) << 16)
            | ((bytes[offset + 2] & 0xFF) << 8)
            | (bytes[offset + 3] & 0xFF);
    }

    private static void write4Bytes(ByteArrayOutputStream out, int value) {
        out.write((value >>> 24) & 0xFF);
        out.write((value >>> 16) & 0xFF);
        out.write((value >>> 8) & 0xFF);
        out.write(value & 0xFF);
    }

}
