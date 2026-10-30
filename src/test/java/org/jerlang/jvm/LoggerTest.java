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
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/**
 * Loads OTP's own logger from the kernel application and runs the parts that
 * do not need the application controller: the module loads through
 * code:ensure_loaded/1 and its pure level comparison works.
 *
 * logger:format_report/1 is not covered yet. It calls lists:flatten/1 inside
 * a try/catch, and a function_clause raised from OTP's lists.beam there is not
 * caught, so the report formatting path still stops inside the runtime.
 *
 * The test is opt-in because it needs an OTP installation:
 *
 * <pre>
 * mvn -Dtest=LoggerTest -Djerlang.otp.dir=/path/to/otp test
 * </pre>
 */
public class LoggerTest extends AbstractVirtualMachineTest {

    private static final String OTP_DIR = "jerlang.otp.dir";
    private static final Atom LOGGER = Atom.of("logger");

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
    public void testLoggerLoadsThroughTheCodeServer() throws Throwable {
        assertEquals(org.jerlang.type.Tuple.of(Atom.of("module"), LOGGER),
            Erlang.apply(Atom.of("code"), Atom.of("ensure_loaded"), List.of(LOGGER)));
    }

    @Test
    public void testCompareLevels() throws Throwable {
        assertEquals(Atom.of("gt"), call("compare_levels", Atom.of("info"), Atom.of("debug")));
        assertEquals(Atom.of("lt"), call("compare_levels", Atom.of("debug"), Atom.of("info")));
        assertEquals(Atom.of("eq"), call("compare_levels", Atom.of("info"), Atom.of("info")));
    }

    private static Term call(String function, Term... args) throws Throwable {
        return Erlang.apply(LOGGER, Atom.of(function), List.of(args));
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

}
