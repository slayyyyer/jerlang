package org.jerlang.jvm;

import static org.junit.Assert.assertEquals;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.Comparator;

import org.jerlang.erts.Erlang;
import org.jerlang.type.Atom;
import org.jerlang.type.Integer;
import org.jerlang.type.List;
import org.jerlang.type.Map;
import org.jerlang.type.Str;
import org.jerlang.type.Term;
import org.jerlang.type.Tuple;
import org.jerlang.vm.VirtualMachine;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/**
 * Runs file_demo.beam, compiled from src/test/erl/file_demo.erl by Erlang/OTP
 * 29, against the native file module.
 *
 * Every test gets its own directory, so a leftover file from one test can not
 * be read by another.
 */
public class FileTest {

    private static final Path SOURCE = Paths.get("src/test/resources/file_demo.beam");
    private static final Path LOCAL = Paths.get("file_demo.beam");

    private Path directory;

    @Before
    public void start() throws Exception {
        Files.copy(SOURCE, LOCAL, StandardCopyOption.REPLACE_EXISTING);
        directory = Files.createTempDirectory("jerlang-file");
        VirtualMachine.instance().start();
    }

    @After
    public void stop() throws Exception {
        VirtualMachine.instance().shutdown();
        Files.deleteIfExists(LOCAL);
        try (java.util.stream.Stream<Path> paths = Files.walk(directory)) {
            paths.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.delete(path);
                } catch (IOException e) {
                    // The operating system cleans its temp directory too.
                }
            });
        }
    }

    @Test
    public void testWriteFileAndReadFileRoundTripEveryByte() throws Throwable {
        assertEquals(Atom.of("ok"), call("write_read_binary"));
    }

    @Test
    public void testOpenWriteReadAndClose() throws Throwable {
        assertEquals(Atom.of("ok"), call("open_write_read"));
    }

    @Test
    public void testReadLineInListMode() throws Throwable {
        assertEquals(Atom.of("ok"), call("read_lines"));
    }

    @Test
    public void testAppendAndExclusiveModes() throws Throwable {
        assertEquals(Atom.of("ok"), call("append_mode"));
    }

    @Test
    public void testReadWriteModeUpdatesInPlace() throws Throwable {
        assertEquals(Atom.of("ok"), call("update_mode"));
    }

    @Test
    public void testListDirReturnsNames() throws Throwable {
        assertEquals(Atom.of("ok"), call("list_dir_names"));
    }

    @Test
    public void testConsultReadsApplicationTerms() throws Throwable {
        assertEquals(Atom.of("ok"), call("consult_terms"));
    }

    @Test
    public void testConsultReadsLiterals() throws Throwable {
        assertEquals(Atom.of("ok"), call("consult_values"));
    }

    @Test
    public void testMissingFileIsEnoent() throws Throwable {
        assertEquals(Atom.of("ok"), call("missing_file"));
    }

    @Test
    public void testPositionAndEof() throws Throwable {
        assertEquals(Atom.of("ok"), call("position_and_eof"));
    }

    @Test
    public void testConsultReportsTheLineOfASyntaxError() throws Throwable {
        assertEquals(Atom.of("ok"), call("bad_consult"));
    }

    /**
     * Maps are part of the term syntax a .app file can use, but the demo can
     * not write a map literal without the map instructions, so the expected
     * value is built here.
     */
    @Test
    public void testConsultReadsMaps() throws Throwable {
        Path file = directory.resolve("maps.config");
        Files.write(file, "%% a map\n#{name => demo, values => [1, 2], inner => #{ok => true}}.\n"
            .getBytes(java.nio.charset.StandardCharsets.UTF_8));

        Map inner = new Map();
        inner.set(Atom.of("ok"), Atom.of("true"));
        Map expected = new Map();
        expected.set(Atom.of("name"), Atom.of("demo"));
        expected.set(Atom.of("values"), List.of(Integer.of(1), Integer.of(2)));
        expected.set(Atom.of("inner"), inner);

        assertEquals(Tuple.of(Atom.of("ok"), List.of(expected)),
            Erlang.apply(Atom.of("file"), Atom.of("consult"), List.of(Str.of(file.toString()))));
    }

    private Term call(String function) throws Throwable {
        return Erlang.apply(Atom.of("file_demo"), Atom.of(function),
            List.of(Str.of(directory.toString())));
    }

}
