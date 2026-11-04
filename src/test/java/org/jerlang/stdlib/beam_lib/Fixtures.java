package org.jerlang.stdlib.beam_lib;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.jerlang.util.ByteUtil;

/**
 * The checked-in BEAM files, which are the oracle for every writer.
 *
 * They were compiled by several OTP releases, so between them they cover both
 * atom chunk layouts, both literal table layouts, modules with and without a
 * lambda table, and 53 files that are stored gzip-wrapped. Decompressing here
 * means the writers only ever see the container bits, which is the only form
 * they are required to produce.
 */
final class Fixtures {

    private Fixtures() {
    }

    static List<Path> paths() throws IOException {
        try (Stream<Path> paths = Files.walk(Paths.get("src/test/resources"))) {
            return paths
                .filter(Files::isRegularFile)
                .filter(path -> path.toString().endsWith(".beam"))
                .sorted()
                .collect(Collectors.toList());
        }
    }

    static byte[] bytes(Path path) throws IOException {
        return ByteUtil.maybe_decompress(Files.readAllBytes(path));
    }

    static AtomChunk atoms(BeamFile file) throws Throwable {
        if (file.has("Atom")) {
            return new AtomChunkReader(chunk(file, "Atom"), stream(file, "Atom")).read();
        }
        return new AtomChunkReader(chunk(file, "AtU8"), stream(file, "AtU8"), true).read();
    }

    /**
     * The literal table, or null: a module without literals does not have one.
     */
    static LiteralTableChunk literals(BeamFile file) throws Throwable {
        if (!file.has("LitT")) {
            return null;
        }
        return new LiteralTableChunkReader(chunk(file, "LitT"), stream(file, "LitT")).read();
    }

    static Chunk chunk(BeamFile file, String id) {
        return new Chunk(ChunkId.of(id), 0, file.payload(id).length);
    }

    static DataInputStream stream(BeamFile file, String id) {
        return new DataInputStream(new ByteArrayInputStream(file.payload(id)));
    }

}
