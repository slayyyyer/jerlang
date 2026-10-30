package org.jerlang.jvm;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assume.assumeTrue;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;

import org.jerlang.AbstractVirtualMachineTest;
import org.jerlang.CodePaths;
import org.jerlang.ModuleRegistry;
import org.jerlang.erts.Erlang;
import org.jerlang.type.Atom;
import org.jerlang.type.List;
import org.jerlang.type.Term;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/**
 * Starts OTP's own supervisor with a gen_server child. The supervisor and the
 * gen_server are the unmodified OTP 29 modules; only the callback modules are
 * compiled for the test.
 *
 * The test is opt-in because it needs an OTP installation:
 *
 * <pre>
 * mvn -Dtest=SupervisorTest -Djerlang.otp.dir=/path/to/otp test
 * </pre>
 */
public class SupervisorTest extends AbstractVirtualMachineTest {

    private static final String OTP_DIR = "jerlang.otp.dir";
    private static final Atom DEMO = Atom.of("ms6_demo");
    private static final String[] BEAMS = {
        "ms6_demo.beam", "ms6_sup.beam", "ms6_worker.beam"
    };

    @Before
    public void startTree() throws Exception {
        String root = System.getProperty(OTP_DIR);
        assumeTrue("set -D" + OTP_DIR + " to an Erlang/OTP installation", root != null);

        CodePaths.reset();
        ModuleRegistry.reset();
        for (String beam : BEAMS) {
            Files.copy(Paths.get("src/test/resources", beam), Paths.get(beam),
                StandardCopyOption.REPLACE_EXISTING);
        }
        CodePaths.addPatha(CodePaths.normalizePath("src/test/resources"));
        CodePaths.addPatha(ebin(root, "stdlib"));
        CodePaths.addPatha(ebin(root, "kernel"));
    }

    @After
    public void stopTree() throws Exception {
        try {
            call("stop_tree");
        } catch (Throwable ignored) {
            // The tree may already be down; the files still have to go.
        }
        CodePaths.reset();
        ModuleRegistry.reset();
        for (String beam : BEAMS) {
            Files.deleteIfExists(Paths.get(beam));
        }
    }

    @Test
    public void testChildIsRestartedAfterItDies() throws Throwable {
        call("start_tree");
        // ping/0 replies pong and answers with the incremented count.
        assertEquals(Atom.of("pong"), call("ping"));
        assertEquals(org.jerlang.type.Integer.of(1), call("count"));

        Term firstPid = call("worker");
        assertEquals(firstPid, call("crash_worker"));

        Term replacement = awaitWorker(firstPid);
        assertNotEquals(firstPid, replacement);
        // A restarted gen_server starts from init/1 again, so the count that
        // the killed child had reached is gone.
        assertEquals(org.jerlang.type.Integer.of(0), call("count"));
        assertEquals(Atom.of("pong"), call("ping"));
        assertEquals(org.jerlang.type.Integer.of(1), call("count"));
    }

    /**
     * The supervisor restarts the child asynchronously, so poll the registry
     * until a new pid shows up.
     */
    private Term awaitWorker(Term previous) throws Throwable {
        for (int attempt = 0; attempt < 100; attempt++) {
            Term current = call("worker");
            if (!current.equals(previous) && !current.equals(Atom.of("undefined"))) {
                return current;
            }
            Thread.sleep(20);
        }
        return call("worker");
    }

    private static Term call(String function, Term... args) throws Throwable {
        return Erlang.apply(DEMO, Atom.of(function), List.of(args));
    }

    private static String ebin(String root, String application) throws IOException {
        Path lib = Paths.get(root, "lib");
        try (DirectoryStream<Path> directories = Files.newDirectoryStream(lib, application + "-*")) {
            for (Path directory : directories) {
                Path ebin = directory.resolve("ebin");
                if (Files.isDirectory(ebin)) {
                    return CodePaths.normalizePath(ebin.toString());
                }
            }
        }
        throw new IOException("no " + application + " in " + lib);
    }

}
