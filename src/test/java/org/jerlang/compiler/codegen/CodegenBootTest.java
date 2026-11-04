package org.jerlang.compiler.codegen;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assume.assumeTrue;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;

import org.jerlang.AbstractVirtualMachineTest;
import org.jerlang.CodePaths;
import org.jerlang.Module;
import org.jerlang.ModuleRegistry;
import org.jerlang.compiler.TestOtp;
import org.jerlang.compiler.parse.Parser;
import org.jerlang.compiler.preprocess.Preprocessor;
import org.jerlang.erts.Erlang;
import org.jerlang.kernel.application_controller.Applications;
import org.jerlang.type.Atom;
import org.jerlang.type.List;
import org.jerlang.type.Term;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/**
 * = A5 application boot
 *
 * The strong form of the A5 exit test: compile the OTP modules that make up an
 * application boot path with JErlang, put those beams ahead of the installed
 * ones, and start the milestone 6 supervision tree. The application callback
 * is still ordinary OTP code; only the six modules below are replaced.
 *
 * `code` and `logger` have built-in implementations in this runtime and are
 * therefore still served from Java; the test asserts the modules that are
 * compiled here and not built in.
 */
public class CodegenBootTest extends AbstractVirtualMachineTest {

    private static final java.util.List<String> BOOT_MODULES = Arrays.asList(
        "stdlib/gen_server",
        "stdlib/supervisor",
        "stdlib/proc_lib",
        "kernel/application",
        "kernel/logger",
        "kernel/code");

    /**
     * The boot modules that the runtime does not also provide as built-ins, so
     * a successful boot proves the compiled beams were used.
     */
    private static final java.util.List<String> COMPILED_BOOT_MODULES = Arrays.asList(
        "gen_server", "supervisor", "proc_lib", "application");

    private static final Atom APPLICATION = Atom.of("application");
    private static final Atom APP = Atom.of("ms6_app");
    private static final Atom WORKER = Atom.of("ms6_worker");
    private static final Atom OK = Atom.of("ok");
    private static final Atom PONG = Atom.of("pong");

    private Path directory;

    @Before
    public void compileBootModules() throws Exception {
        Path erl = TestOtp.erl();
        assumeTrue("no Erlang installation found", erl != null);
        assumeTrue("the Erlang installation has no lib directory",
            erl.getParent().getParent().resolve("lib").toFile().isDirectory());

        Path root = erl.getParent().getParent();
        Path lib = root.resolve("lib");
        java.util.List<String> includes = new ArrayList<>();
        try (DirectoryStream<Path> applications = Files.newDirectoryStream(lib)) {
            for (Path application : applications) {
                Path include = application.resolve("include");
                if (Files.isDirectory(include)) {
                    includes.add(include.toString());
                }
            }
        }
        includes.add(lib.toString());
        java.util.List<String> roots = Collections.singletonList(lib.toString());

        directory = Files.createTempDirectory("codegen-boot");
        for (String module : BOOT_MODULES) {
            Path source = source(lib, module);
            java.util.List<Term> forms = Parser.parseForms(
                Preprocessor.scanFile(source, includes, roots, 29));
            Path beam = directory.resolve(source.getFileName().toString()
                .replace(".erl", ".beam"));
            Files.write(beam, Codegen.compile(forms));
        }

        CodePaths.reset();
        ModuleRegistry.reset();
        Applications.reset();
        // addPatha inserts at the front, so add the fallback paths first and
        // the compiled boot modules last: those must win over installed OTP.
        CodePaths.addPatha(ebin(root, "stdlib"));
        CodePaths.addPatha(ebin(root, "kernel"));
        CodePaths.addPatha(CodePaths.normalizePath("src/test/resources"));
        CodePaths.addPatha(directory.toString());
    }

    @After
    public void cleanUp() {
        try {
            call(APPLICATION, Atom.of("stop"), List.of(APP));
        } catch (Throwable ignored) {
            // The application may never have started.
        }
        Applications.reset();
        CodePaths.reset();
        ModuleRegistry.reset();
        if (directory != null) {
            try (DirectoryStream<Path> files = Files.newDirectoryStream(directory)) {
                for (Path file : files) {
                    Files.deleteIfExists(file);
                }
            } catch (IOException ignored) {
                // Temporary files are diagnostics only.
            }
            try {
                Files.deleteIfExists(directory);
            } catch (IOException ignored) {
                // Temporary files are diagnostics only.
            }
        }
    }

    @Test(timeout = 120000)
    public void compiledOtpApplicationBootsAndRuns() throws Throwable {        assertEquals(OK, call(APPLICATION, Atom.of("start"), List.of(APP)));

        for (String module : COMPILED_BOOT_MODULES) {
            Module loaded = ModuleRegistry.get(Atom.of(module));
            assertNotNull(module + " was not loaded during boot", loaded);
            assertFalse(module + " was served by a built-in instead of the compiled beam",
                loaded.isInternal());
            assertNotNull(module + " has no beam file", loaded.beamFile());
            assertEquals(module + " was loaded from the installed OTP instead of the compiled beam",
                directory.toFile().getCanonicalFile(),
                loaded.beamFile().getCanonicalFile().getParentFile());
        }

        assertEquals(PONG, call(WORKER, Atom.of("ping"), List.nil));
        assertEquals(OK, call(APPLICATION, Atom.of("stop"), List.of(APP)));
    }

    private static Path source(Path lib, String module) throws IOException {
        int slash = module.indexOf('/');
        String application = module.substring(0, slash);
        String name = module.substring(slash + 1);
        try (DirectoryStream<Path> directories = Files.newDirectoryStream(lib,
            application + "-*")) {
            for (Path directory : directories) {
                Path source = directory.resolve("src").resolve(name + ".erl");
                if (Files.isRegularFile(source)) {
                    return source;
                }
            }
        }
        throw new IOException("no source for " + module + " in " + lib);
    }

    private static String ebin(Path root, String application) throws IOException {
        Path lib = root.resolve("lib");
        try (DirectoryStream<Path> directories = Files.newDirectoryStream(lib,
            application + "-*")) {
            for (Path directory : directories) {
                Path ebin = directory.resolve("ebin");
                if (Files.isDirectory(ebin)) {
                    return CodePaths.normalizePath(ebin.toString());
                }
            }
        }
        throw new IOException("no " + application + " in " + lib);
    }

    private static Term call(Atom module, Atom function, List arguments) throws Throwable {
        return Erlang.apply(module, function, arguments);
    }

}
