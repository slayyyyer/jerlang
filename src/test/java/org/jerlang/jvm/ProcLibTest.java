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
import org.jerlang.type.List;
import org.jerlang.type.Term;
import org.jerlang.type.Tuple;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/**
 * Runs OTP's own proc_lib on this runtime.
 *
 * The demo module is compiled by OTP 29 and proc_lib is loaded from an OTP
 * installation, so nothing in the path is a JErlang rewrite. The test is
 * opt-in because it needs the installation:
 *
 * <pre>
 * mvn -Dtest=ProcLibTest -Djerlang.otp.dir=/path/to/otp test
 * </pre>
 *
 * Both cases go through paths the interpreter has to get right for any OTP
 * behaviour: a terminating cross-module tail call (call_ext_last, whose frame
 * has to be released), a fun built in another module (call_fun2), and a
 * receive that parks inside proc_lib's own activation, below its caller's.
 */
public class ProcLibTest extends AbstractVirtualMachineTest {

    private static final String OTP_DIR = "jerlang.otp.dir";
    private static final Atom OK = Atom.of("ok");
    private static final Atom TRUE = Atom.of("true");

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
    public void procLibSpawnRunsTheFunInANewProcess() throws Throwable {
        assertEquals(Tuple.of(OK, TRUE), call("proc_lib_demo", "spawn_value"));
    }

    @Test
    public void procLibStartWaitsForInitAck() throws Throwable {
        assertEquals(Tuple.of(OK, TRUE), call("proc_lib_demo", "start_value"));
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

    private static Term call(String module, String function, Term... args) throws Throwable {
        return Erlang.apply(Atom.of(module), Atom.of(function), List.of(args));
    }

}
