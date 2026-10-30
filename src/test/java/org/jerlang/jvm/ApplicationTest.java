package org.jerlang.jvm;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeTrue;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import org.jerlang.AbstractVirtualMachineTest;
import org.jerlang.CodePaths;
import org.jerlang.ModuleRegistry;
import org.jerlang.erts.Erlang;
import org.jerlang.kernel.application_controller.Applications;
import org.jerlang.type.Atom;
import org.jerlang.type.Integer;
import org.jerlang.type.List;
import org.jerlang.type.PID;
import org.jerlang.type.Term;
import org.jerlang.type.Tuple;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/**
 * The milestone 6 exit test: a supervision tree with one gen_server child is
 * started by application:start/1 from an unmodified .app resource file, the
 * child is killed and restarted by OTP's supervisor within the configured
 * intensity, and application:stop/1 shuts the tree down again.
 *
 * The test is opt-in because it needs an OTP installation:
 *
 * <pre>
 * mvn -Dtest=ApplicationTest -Djerlang.otp.dir=/path/to/otp test
 * </pre>
 */
public class ApplicationTest extends AbstractVirtualMachineTest {

    private static final String OTP_DIR = "jerlang.otp.dir";
    private static final Atom APPLICATION = Atom.of("application");
    private static final Atom OK = Atom.of("ok");
    private static final Atom UNDEFINED = Atom.of("undefined");
    private static final Atom DEMO = Atom.of("ms6_demo");
    private static final Atom APP = Atom.of("ms6_app");
    private static final Atom INCLUDED = Atom.of("ms6_included");
    private static final Atom ERROR = Atom.of("error");

    @Before
    public void startApplication() throws Exception {
        String root = System.getProperty(OTP_DIR);
        assumeTrue("set -D" + OTP_DIR + " to an Erlang/OTP installation", root != null);

        CodePaths.reset();
        ModuleRegistry.reset();
        Applications.reset();
        CodePaths.addPatha(CodePaths.normalizePath("src/test/resources"));
        CodePaths.addPatha(ebin(root, "stdlib"));
        CodePaths.addPatha(ebin(root, "kernel"));
    }

    @After
    public void stopApplication() {
        try {
            call(APPLICATION, Atom.of("stop"), List.of(APP));
        } catch (Throwable ignored) {
            // The application may already be down.
        }
        try {
            call(Atom.of("persistent_term"), Atom.of("erase"),
                List.of(Atom.of("ms6_start_phase")));
        } catch (Throwable ignored) {
            // Nothing was stored when the application never started.
        }
        Applications.reset();
        CodePaths.reset();
        ModuleRegistry.reset();
    }

    @Test
    public void testApplicationStartRestartAndStop() throws Throwable {
        assertEquals(OK, call(APPLICATION, Atom.of("start"), List.of(APP)));
        // .app parsing: the environment from the resource file is visible.
        assertEquals(Tuple.of(Atom.of("ok"), Integer.of(3)),
            call(APPLICATION, Atom.of("get_env"), List.of(APP, Atom.of("restart_intensity"))));
        assertTrue(isRunning(APP));
        assertTrue(isLoaded(APP));
        // The declared start phase ran in the application callback.
        assertEquals(Atom.of("phase_ran"),
            call(Atom.of("persistent_term"), Atom.of("get"),
                List.of(Atom.of("ms6_start_phase"))));
        // The included application was started with its parent.
        assertTrue(isRunning(INCLUDED));
        assertEquals(Tuple.of(Atom.of("ok"),
                List.of(APP, Atom.of("ms6_sup"), Atom.of("ms6_worker"))),
            call(APPLICATION, Atom.of("get_key"), List.of(APP, Atom.of("modules"))));

        assertEquals(Atom.of("pong"), call(DEMO, Atom.of("ping"), List.of()));
        assertEquals(Integer.of(1), call(DEMO, Atom.of("count"), List.of()));

        // Three crashes in a row fit the {one_for_one, 3, 10} intensity of
        // the tree, so the supervisor restarts the child every time.
        Term previous = call(DEMO, Atom.of("worker"), List.of());
        for (int crash = 0; crash < 3; crash++) {
            assertEquals(previous, call(DEMO, Atom.of("crash_worker"), List.of()));
            Term replacement = awaitWorker(previous);
            assertNotEquals(previous, replacement);
            assertEquals(Atom.of("pong"), call(DEMO, Atom.of("ping"), List.of()));
            previous = replacement;
        }
        assertEquals(Integer.of(1), call(DEMO, Atom.of("count"), List.of()));

        assertEquals(OK, call(APPLICATION, Atom.of("stop"), List.of(APP)));
        assertEquals(UNDEFINED, call(DEMO, Atom.of("worker"), List.of()));
        assertEquals(UNDEFINED, call(DEMO, Atom.of("supervisor"), List.of()));
        assertFalse(isRunning(APP));
        assertFalse(isRunning(INCLUDED));
    }

    /**
     * A tree that dies on its own is no longer a running application, even
     * though there is no application_master to report the exit.
     */
    @Test
    public void testDeadTreeLeavesTheRunningSet() throws Throwable {
        assertEquals(OK, call(APPLICATION, Atom.of("start"), List.of(APP)));
        Term master = call(Atom.of("application_controller"),
            Atom.of("get_master"), List.of(APP));
        assertTrue("the started application has a top pid", master instanceof PID);

        call(Atom.of("erlang"), Atom.of("exit"), List.of(master, Atom.of("kill")));
        for (int attempt = 0; attempt < 100 && isRunning(APP); attempt++) {
            Thread.sleep(20);
        }
        assertFalse(isRunning(APP));
        assertEquals(Tuple.of(ERROR, Tuple.of(Atom.of("not_started"), APP)),
            call(APPLICATION, Atom.of("stop"), List.of(APP)));
    }

    private static boolean isRunning(Term application) throws Throwable {
        List applications = call(APPLICATION, Atom.of("which_applications"), List.of()).toList();
        while (applications.length() > 0) {
            Term entry = applications.head();
            if (entry instanceof Tuple && application.equals(((Tuple) entry).element(1))) {
                return true;
            }
            applications = applications.tail();
        }
        return false;
    }

    private static boolean isLoaded(Term application) throws Throwable {
        List applications = call(APPLICATION, Atom.of("loaded_applications"), List.of()).toList();
        while (applications.length() > 0) {
            Term entry = applications.head();
            if (entry instanceof Tuple && application.equals(((Tuple) entry).element(1))) {
                return true;
            }
            applications = applications.tail();
        }
        return false;
    }

    /**
     * The supervisor restarts the child asynchronously, so poll the registry
     * until a new pid shows up.
     */
    private static Term awaitWorker(Term previous) throws Throwable {
        for (int attempt = 0; attempt < 100; attempt++) {
            Term current = call(DEMO, Atom.of("worker"), List.of());
            if (!current.equals(previous) && !current.equals(UNDEFINED)) {
                return current;
            }
            Thread.sleep(20);
        }
        return call(DEMO, Atom.of("worker"), List.of());
    }

    private static Term call(Atom module, Atom function, List arguments) throws Throwable {
        return Erlang.apply(module, function, arguments);
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
