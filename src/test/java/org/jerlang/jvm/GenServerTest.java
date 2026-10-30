package org.jerlang.jvm;

import static org.junit.Assert.assertEquals;
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
import org.jerlang.type.Atom;
import org.jerlang.type.Integer;
import org.jerlang.type.List;
import org.jerlang.type.Term;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/**
 * Runs OTP's own gen_server, gen and sys on this runtime, driven by a
 * callback module compiled from src/test/erl/gen_server_demo.erl.
 *
 * Nothing in the path is a JErlang rewrite: the server is started by
 * proc_lib, enters the gen_server loop, and answers calls and casts. The test
 * is opt-in because it needs an OTP installation:
 *
 * <pre>
 * mvn -Dtest=GenServerTest -Djerlang.otp.dir=/path/to/otp test
 * </pre>
 */
public class GenServerTest extends AbstractVirtualMachineTest {

    private static final String OTP_DIR = "jerlang.otp.dir";
    private static final Atom MODULE = Atom.of("gen_server_demo");

    @Before
    public void addOtpToTheCodePath() throws IOException {
        String root = System.getProperty(OTP_DIR);
        assumeTrue("set -D" + OTP_DIR + " to an Erlang/OTP installation", root != null);

        CodePaths.reset();
        ModuleRegistry.reset();
        CodePaths.addPatha(CodePaths.normalizePath("src/test/resources"));
        CodePaths.addPatha(ebin(root, "stdlib"));
        CodePaths.addPatha(ebin(root, "kernel"));
    }

    @After
    public void resetCodeServer() {
        CodePaths.reset();
        ModuleRegistry.reset();
    }

    @Test
    public void testStartCallAndStop() throws Throwable {
        assertEquals(Atom.of("pong"), call("ping"));
        assertEquals(Atom.of("ok"), call("start", Integer.of(0)));
        // The server is registered under its module name.
        assertEquals(Integer.of(5), call("add", Atom.of("gen_server_demo"), Integer.of(5)));
        assertEquals(Integer.of(8), call("add", Atom.of("gen_server_demo"), Integer.of(3)));
        assertEquals(Integer.of(8), call("get", Atom.of("gen_server_demo")));
        assertEquals(Atom.of("ok"), call("stop", Atom.of("gen_server_demo")));
    }

    @Test
    public void testCastAndHibernateAfter() throws Throwable {
        assertEquals(Atom.of("ok"), call("start", Integer.of(0)));
        assertEquals(Atom.of("ok"), call("bump", Atom.of("gen_server_demo")));
        assertEquals(Integer.of(1), call("get", Atom.of("gen_server_demo")));
        assertEquals(Atom.of("ok"), call("stop", Atom.of("gen_server_demo")));
    }

    @Test
    public void testHibernateAfterWakesOnTheNextCall() throws Throwable {
        // With hibernate_after set, an idle gen_server calls erlang:hibernate/0.
        // The runtime has no hibernated state, so the BIF must still exist and
        // let the receive in loop_wakeup wait for the next request.
        assertEquals(Atom.of("ok"), call("start_hibernating", Integer.of(0)));
        Thread.sleep(100);
        assertEquals(Integer.of(7), call("add", Atom.of("gen_server_demo"), Integer.of(7)));
        assertEquals(Atom.of("ok"), call("stop", Atom.of("gen_server_demo")));
    }

    /**
     * The ebin directory of an application in an OTP installation.
     */
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

    private static Term call(String function, Term... args) throws Throwable {
        return Erlang.apply(MODULE, Atom.of(function), List.of(args));
    }

}
