package org.jerlang.jvm;

import static org.junit.Assert.assertEquals;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.Comparator;

import org.jerlang.AbstractVirtualMachineTest;
import org.jerlang.CodePaths;
import org.jerlang.ModuleRegistry;
import org.jerlang.erts.Emulator;
import org.jerlang.erts.Erlang;
import org.jerlang.type.Atom;
import org.jerlang.type.Integer;
import org.jerlang.type.List;
import org.jerlang.type.Str;
import org.jerlang.type.Term;
import org.jerlang.type.Tuple;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/**
 * The code server: -pa and -pz, ERL_LIBS, and the code module that reports
 * what the search path resolves to.
 *
 * The BEAM files are installed into temporary directories by the tests, so
 * nothing is ever loaded from the working directory and a failure points at
 * the search path rather than at a stale file.
 */
public class CodeServerTest extends AbstractVirtualMachineTest {

    private static final Path RESOURCES = Paths.get("src/test/resources");

    private Path root;

    @Before
    public void createWorkspace() throws Exception {
        root = Files.createTempDirectory("jerlang-code-server");
        CodePaths.reset();
        ModuleRegistry.reset();
    }

    @After
    public void removeWorkspace() throws Exception {
        CodePaths.reset();
        ModuleRegistry.reset();
        try (java.util.stream.Stream<Path> paths = Files.walk(root)) {
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
    public void testWhichFindsABeamFileOnTheCodePath() throws Throwable {
        Path ebin = ebin(root.resolve("app"));
        beam("code_demo", ebin);

        assertEquals(Atom.of("true"), call("code", "add_patha", Str.of(path(ebin))));
        assertEquals(Atom.of(path(ebin) + "/code_demo.beam"),
            call("code", "which", Atom.of("code_demo")));
    }

    @Test
    public void testWhichReportsPreloadedAndMissingModules() throws Throwable {
        // erlang is implemented by the runtime and has no BEAM file.
        assertEquals(Atom.of("preloaded"), call("code", "which", Atom.of("erlang")));
        assertEquals(Atom.of("non_existing"), call("code", "which", Atom.of("no_such_module")));
    }

    @Test
    public void testPaFlagPutsDirectoriesInFrontInOrder() throws Throwable {
        Path first = Files.createDirectories(root.resolve("first"));
        Path second = Files.createDirectories(root.resolve("second"));
        beam("code_extra", first);

        Emulator.parse(new String[] { "-pa", path(first), path(second) });

        List path = call("code", "get_path").toList();
        assertEquals(Str.of(path(first)), path.head());
        assertEquals(Str.of(path(second)), path.tail().head());
        assertEquals(Atom.of(path(first) + "/code_extra.beam"),
            call("code", "which", Atom.of("code_extra")));
    }

    @Test
    public void testPzFlagAppendsTheDirectory() throws Throwable {
        Path last = Files.createDirectories(root.resolve("last"));

        Emulator.parse(new String[] { "-pz", path(last) });

        assertEquals(Str.of(path(last)), last(call("code", "get_path").toList()));
    }

    @Test
    public void testErlLibsAddsTheEbinOfEveryApplication() throws Throwable {
        Path lib = Files.createDirectories(root.resolve("lib"));
        Path application = Files.createDirectories(lib.resolve("myapp-1.0"));
        Path ebin = Files.createDirectories(application.resolve("ebin"));
        Files.createDirectories(application.resolve("priv"));
        beam("code_demo", ebin);

        CodePaths.addErlLibs(lib.toString());

        assertEquals(Str.of(path(application)), call("code", "lib_dir", Atom.of("myapp")));
        assertEquals(Str.of(path(application) + "/include"),
            call("code", "lib_dir", Atom.of("myapp"), Str.of("include")));
        assertEquals(Str.of(path(application) + "/priv"),
            call("code", "priv_dir", Atom.of("myapp")));
        assertEquals(Atom.of(path(ebin) + "/code_demo.beam"),
            call("code", "which", Atom.of("code_demo")));
        assertEquals(Str.of(path(root)), call("code", "root_dir"));
    }

    @Test
    public void testErlLibsAcceptsADirectoryThatIsItselfAnApplication() throws Throwable {
        Path application = Files.createDirectories(root.resolve("direct-1.0"));
        Path ebin = Files.createDirectories(application.resolve("ebin"));
        beam("code_extra", ebin);

        CodePaths.addErlLibs(application.toString());

        assertEquals(Str.of(path(application)), call("code", "lib_dir", Atom.of("direct")));
        assertEquals(Atom.of(path(ebin) + "/code_extra.beam"),
            call("code", "which", Atom.of("code_extra")));
    }

    @Test
    public void testUnknownApplicationHasNoLibraryDirectory() throws Throwable {
        Tuple badName = Tuple.of(Atom.of("error"), Atom.of("bad_name"));
        assertEquals(badName, call("code", "lib_dir", Atom.of("no_such_app")));
        assertEquals(badName, call("code", "priv_dir", Atom.of("no_such_app")));
    }

    @Test
    public void testLoadFileRegistersTheModule() throws Throwable {
        Path ebin = ebin(root.resolve("loading"));
        beam("code_demo", ebin);
        CodePaths.addPatha(path(ebin));

        assertEquals(Tuple.of(Atom.of("module"), Atom.of("code_demo")),
            call("code", "load_file", Atom.of("code_demo")));
        assertEquals(Tuple.of(Atom.of("file"), Str.of(path(ebin) + "/code_demo.beam")),
            call("code", "is_loaded", Atom.of("code_demo")));
        assertEquals(Atom.of("false"), call("code", "is_loaded", Atom.of("code_extra")));
        assertEquals(Tuple.of(Atom.of("error"), Atom.of("nofile")),
            call("code", "load_file", Atom.of("code_extra")));
    }

    @Test
    public void testCompiledErlangUsesTheCodeServer() throws Throwable {
        Path ebin = ebin(root.resolve("erlang"));
        beam("code_demo", ebin);
        beam("code_extra", ebin);

        assertEquals(Atom.of("true"), call("code", "add_patha", Str.of(path(ebin))));

        assertEquals(Atom.of(path(ebin) + "/code_demo.beam"), call("code_demo", "which"));
        assertEquals(Atom.of("true"),
            call("code_demo", "which_in", Str.of(path(ebin) + "/code_demo.beam")));
        assertEquals(Str.of(path(ebin)), call("code_demo", "first_path"));
        assertEquals(Atom.of("not_loaded"),
            call("code_demo", "loaded", Atom.of("code_extra")));
        assertEquals(Integer.of(42), call("code_demo", "add_and_load", Str.of(path(ebin))));
        assertEquals(Atom.of("loaded"),
            call("code_demo", "loaded", Atom.of("code_extra")));
    }

    @Test
    public void testCompiledErlangReadsApplicationDirectories() throws Throwable {
        Path lib = Files.createDirectories(root.resolve("lib"));
        Path application = Files.createDirectories(lib.resolve("myapp-1.0"));
        Path ebin = Files.createDirectories(application.resolve("ebin"));
        Files.createDirectories(application.resolve("priv"));
        beam("code_demo", ebin);

        CodePaths.addErlLibs(lib.toString());

        assertEquals(Str.of(path(application)), call("code_demo", "lib_dir", Atom.of("myapp")));
        assertEquals(Str.of(path(application) + "/priv"),
            call("code_demo", "priv_dir", Atom.of("myapp")));
        assertEquals(Str.of(path(root)), call("code_demo", "root_dir"));
    }

    /**
     * A module directory with the ebin subdirectory an application has.
     */
    private static Path ebin(Path application) throws IOException {
        return Files.createDirectories(application.resolve("ebin"));
    }

    private static void beam(String module, Path directory) throws IOException {
        Files.copy(RESOURCES.resolve(module + ".beam"),
            directory.resolve(module + ".beam"), StandardCopyOption.REPLACE_EXISTING);
    }

    private static String path(Path path) {
        return CodePaths.normalizePath(path.toString());
    }

    private static Term call(String module, String function, Term... args) throws Throwable {
        return Erlang.apply(Atom.of(module), Atom.of(function), List.of(args));
    }

    private static Term last(List list) {
        while (list.tail() != List.nil) {
            list = list.tail();
        }
        return list.head();
    }

}
