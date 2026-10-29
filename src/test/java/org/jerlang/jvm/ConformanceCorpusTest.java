package org.jerlang.jvm;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Arrays;


import org.jerlang.erts.Erlang;
import org.jerlang.type.Atom;
import org.jerlang.type.List;
import org.jerlang.vm.VirtualMachine;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/**
 * The 0.5 exit test: a checked-in corpus with one module per Erlang construct
 * loads and runs. Every module was compiled by OTP 29; a construct that needs
 * an unimplemented opcode fails here instead of in a user's program.
 *
 * The sources live in src/test/erl/corpus and are compiled with:
 *   erlc -o src/test/resources/corpus src/test/erl/corpus/*.erl
 */
public class ConformanceCorpusTest {

    private static final Path DIRECTORY = Paths.get("src/test/resources/corpus");
    private final java.util.List<Path> copied = new ArrayList<>();

    @Before
    public void start() throws Exception {
        for (Path beam : beams()) {
            Path local = Paths.get(beam.getFileName().toString());
            Files.copy(beam, local, StandardCopyOption.REPLACE_EXISTING);
            copied.add(local);
        }
        VirtualMachine.instance().start();
    }

    @After
    public void stop() throws Exception {
        VirtualMachine.instance().shutdown();
        for (Path local : copied) {
            Files.deleteIfExists(local);
        }
    }

    @Test
    public void testEveryConstructRuns() throws Throwable {
        java.util.List<Path> beams = beams();
        assertTrue("the corpus must contain modules", beams.size() >= 5);

        for (Path beam : beams) {
            String filename = beam.getFileName().toString();
            String module = filename.substring(0, filename.length() - ".beam".length());
            try {
                assertEquals("corpus module " + module + " did not run",
                    Atom.of("ok"),
                    Erlang.apply(Atom.of(module), Atom.of("run"), List.nil));
            } catch (Throwable failure) {
                throw new AssertionError("corpus module " + module + " failed: " + failure,
                    failure);
            }
        }
    }

    private static java.util.List<Path> beams() throws Exception {
        File[] files = DIRECTORY.toFile().listFiles(
            file -> file.getName().endsWith(".beam"));
        Arrays.sort(files);
        java.util.List<Path> result = new ArrayList<>();
        for (File file : files) {
            result.add(file.toPath());
        }
        return result;
    }

}
