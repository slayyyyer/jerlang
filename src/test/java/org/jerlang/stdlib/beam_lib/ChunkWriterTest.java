package org.jerlang.stdlib.beam_lib;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertTrue;

import java.nio.file.Path;
import java.util.List;

import org.junit.Test;

/**
 * A1.3 to A1.8: every chunk writer, checked against the corpus.
 *
 * Each writer replaces a parsed chunk with bytes derived from its model, and
 * the assertion is that those bytes are the bytes that were read. A writer that
 * is only semantically correct passes a load test and fails this one, which is
 * the point: the digest covers these payloads.
 */
public class ChunkWriterTest {

    private interface Writer {
        byte[] write(BeamFile file, byte[] payload) throws Throwable;
    }

    @Test
    public void atomChunkIsWrittenBack() throws Exception {
        check("Atom", (file, payload) -> AtomChunkWriter.write(Fixtures.atoms(file), payload));
        check("AtU8", (file, payload) -> AtomChunkWriter.write(Fixtures.atoms(file), payload));
    }

    @Test
    public void functionTablesAreWrittenBack() throws Exception {
        check("ExpT", (file, payload) -> TableChunkWriter.exports(
            new ExportTableChunkReader(Fixtures.chunk(file, "ExpT"), Fixtures.stream(file, "ExpT"),
                Fixtures.atoms(file)).read(), Fixtures.atoms(file)));
        check("ImpT", (file, payload) -> TableChunkWriter.imports(
            new ImportTableChunkReader(Fixtures.chunk(file, "ImpT"), Fixtures.stream(file, "ImpT"),
                Fixtures.atoms(file)).read(), Fixtures.atoms(file)));
        check("LocT", (file, payload) -> TableChunkWriter.locals(
            new LocalFunctionTableChunkReader(Fixtures.chunk(file, "LocT"),
                Fixtures.stream(file, "LocT"), Fixtures.atoms(file)).read(),
            Fixtures.atoms(file)));
        check("StrT", (file, payload) -> TableChunkWriter.strings(
            new StringTableChunkReader(Fixtures.chunk(file, "StrT"), Fixtures.stream(file, "StrT"))
                .read()));
    }

    @Test
    public void lambdaTableIsWrittenBack() throws Exception {
        check("FunT", (file, payload) -> FunctionTableChunkWriter.write(
            new FunctionTableChunkReader(Fixtures.chunk(file, "FunT"), Fixtures.stream(file, "FunT"),
                Fixtures.atoms(file)).read(), Fixtures.atoms(file)));
    }

    @Test
    public void lineTableIsWrittenBack() throws Exception {
        check("Line", (file, payload) -> LineTableChunkWriter.write(
            new LineTableChunkReader(Fixtures.chunk(file, "Line"), Fixtures.stream(file, "Line"),
                Fixtures.atoms(file)).read()));
    }

    @Test
    public void codeChunkIsWrittenBack() throws Exception {
        check("Code", (file, payload) -> CodeChunkWriter.write(payload, Fixtures.atoms(file),
            Fixtures.literals(file)));
    }

    /**
     * A1.5. The literal table is carried, not re-encoded; the reasons are in
     * {@link TermChunkWriter}. Both storage forms occur in the corpus, so this
     * also records that the milestone was measured against both.
     */
    @Test
    public void literalTableIsCarried() throws Throwable {
        int uncompressed = 0;
        int compressed = 0;
        for (Path path : Fixtures.paths()) {
            BeamFile file = BeamFile.read(Fixtures.bytes(path));
            if (!file.has("LitT")) {
                continue;
            }
            byte[] payload = file.payload("LitT");
            assertArrayEquals(path.toString() + " LitT", payload,
                TermChunkWriter.literals(Fixtures.literals(file), payload));
            if (BeamBytes.read4(payload, 0) == 0) {
                uncompressed++;
            } else {
                compressed++;
            }
        }
        assertTrue("no uncompressed literal table in the corpus", uncompressed > 0);
        assertTrue("no compressed literal table in the corpus", compressed > 0);
    }

    private static void check(String id, Writer writer) throws Exception {
        int checked = 0;
        List<Path> fixtures = Fixtures.paths();
        for (Path path : fixtures) {
            BeamFile file = BeamFile.read(Fixtures.bytes(path));
            if (!file.has(id)) {
                continue;
            }
            byte[] payload = file.payload(id);
            byte[] emitted;
            try {
                emitted = writer.write(file, payload);
            } catch (Throwable throwable) {
                throw new AssertionError("can not write " + id + " in " + path, throwable);
            }
            assertArrayEquals(path.toString() + " " + id, payload, emitted);
            checked++;
        }
        assertTrue(id + " does not occur in the corpus", checked > 0);
    }

}
