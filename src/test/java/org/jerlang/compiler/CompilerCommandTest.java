package org.jerlang.compiler;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

import org.jerlang.FunctionSignature;
import org.jerlang.Module;
import org.jerlang.ModuleLoader;
import org.jerlang.ModuleRegistry;
import org.jerlang.type.Atom;
import org.jerlang.type.Integer;
import org.jerlang.type.Term;
import org.jerlang.vm.VirtualMachine;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/**
 * = A6 command exit test
 *
 * The checked-in corpus is compiled through the shipped command, not through
 * the in-process helpers the A5 tests use. That is the difference the milestone
 * is about: the command, with no `erlc` anywhere in the path, has to produce
 * beams that JErlang then loads and runs. A second test exercises the options a
 * build actually needs: an `-include` header, an `-include_lib` application
 * root, `-D` macros, `-W`, `-Werror`, `-o`, and `+debug_info`.
 */
public class CompilerCommandTest {

    private static final Path CORPUS = Paths.get("src/test/erl/corpus");

    @Before
    public void startVirtualMachine() {
        VirtualMachine.instance().start();
    }

    @After
    public void stop() {
        VirtualMachine.instance().shutdown();
    }

    @Test
    public void corpusCompilesThroughTheCommandAndRuns() throws Throwable {
        Path output = Files.createTempDirectory("jerlc-corpus");
        List<String> arguments = new ArrayList<>();
        arguments.add("-o");
        arguments.add(output.toString());
        List<Path> sources = corpus();
        for (Path source : sources) {
            arguments.add(source.toString());
        }

        Run run = run(arguments);
        assertEquals(run.err, 0, run.status);
        for (Path source : sources) {
            String name = source.getFileName().toString()
                .substring(0, source.getFileName().toString().length() - ".erl".length());
            Path beam = output.resolve(name + ".beam");
            assertTrue("the command did not write " + beam, Files.isRegularFile(beam));
            Module module = load(name, beam);
            try {
                assertEquals(name + " did not return ok", Atom.of("ok"),
                    apply(module, "run", 0));
            } finally {
                ModuleRegistry.unregister(Atom.of(name));
            }
        }
    }

    @Test
    public void commandAppliesIncludesDefinesWarningsAndLibraryRoots() throws Throwable {
        Path root = Files.createTempDirectory("jerlc-options");
        Path include = Files.createDirectories(root.resolve("include"));
        Files.write(include.resolve("defs.hrl"),
            Collections.singletonList("-define(FROM_HEADER, 40)."));
        Path library = Files.createDirectories(root.resolve("lib/myapp-1.0/include"));
        Files.write(library.resolve("defs.hrl"),
            Collections.singletonList("-define(FROM_LIB, 1000)."));

        Path source = root.resolve("sample.erl");
        Files.write(source, Arrays.asList(
            "-module(sample).",
            "-export([value/0, flag/0, from_lib/0, default_flag/0]).",
            "-include(\"defs.hrl\").",
            "-include_lib(\"myapp/include/defs.hrl\").",
            "value() -> ?FROM_HEADER + ?FROM_CLI.",
            "flag() -> ?FLAG.",
            "from_lib() -> ?FROM_LIB.",
            "default_flag() -> ?DEFAULT.",
            "unused() -> 1."));

        Path output = root.resolve("out");
        Run run = run(Arrays.asList(
            "-o", output.toString(),
            "-I", include.toString(),
            "-pa", root.resolve("lib").toString(),
            "-D", "FROM_CLI=2",
            "-D", "FLAG=yes",
            "-D", "DEFAULT",
            "-W", "+debug_info", source.toString()));
        assertEquals(run.err, 0, run.status);
        assertTrue("expected a warning in: " + run.err,
            run.err.contains("Warning:") && run.err.contains("unused/0 is unused"));

        Module module = load("sample", output.resolve("sample.beam"));
        try {
            assertEquals(Integer.of(42), apply(module, "value", 0));
            assertEquals(Atom.of("yes"), apply(module, "flag", 0));
            assertEquals(Integer.of(1000), apply(module, "from_lib", 0));
            assertEquals(Atom.of("true"), apply(module, "default_flag", 0));
        } finally {
            ModuleRegistry.unregister(Atom.of("sample"));
        }

        Path strict = root.resolve("strict");
        Run failed = run(Arrays.asList(
            "-o", strict.toString(),
            "-I", include.toString(),
            "-pa", root.resolve("lib").toString(),
            "-D", "FROM_CLI=2",
            "-D", "FLAG=yes",
            "-D", "DEFAULT",
            "-Werror", source.toString()));
        assertNotEquals(failed.err, 0, failed.status);
        assertTrue("expected a warnings-as-errors message in: " + failed.err,
            failed.err.contains("warnings being treated as errors"));
        assertFalse("warnings-as-errors must not write a beam",
            Files.exists(strict.resolve("sample.beam")));
    }

    @Test
    public void unknownCompilerOptionIsRejected() {
        Run run = run(Collections.singletonList("+parse_transform"));
        assertEquals(2, run.status);
        assertTrue(run.err.contains("unsupported compiler option"));
    }

    private static Module load(String name, Path beam) throws Throwable {
        ModuleRegistry.unregister(Atom.of(name));
        Module module = ModuleLoader.load(Atom.of(name), beam.toFile());
        assertNotNull("JErlang could not load " + name, module);
        ModuleRegistry.register(module);
        return module;
    }

    private static Term apply(Module module, String function, int arity) throws Throwable {
        return module.apply(new FunctionSignature(module.name(), Atom.of(function),
            Integer.of(arity)), org.jerlang.type.List.nil);
    }

    private static Run run(List<String> arguments) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        int status = CompilerCommand.run(arguments.toArray(new String[0]),
            new PrintStream(out, true, StandardCharsets.UTF_8),
            new PrintStream(err, true, StandardCharsets.UTF_8));
        return new Run(status, out.toString(StandardCharsets.UTF_8),
            err.toString(StandardCharsets.UTF_8));
    }

    private static List<Path> corpus() throws Exception {
        List<Path> sources = new ArrayList<>();
        try (java.util.stream.Stream<Path> files = Files.list(CORPUS)) {
            files.filter(path -> path.getFileName().toString().endsWith(".erl"))
                .sorted(Comparator.comparing(Path::toString))
                .forEach(sources::add);
        }
        assertFalse("the codegen corpus must not be empty", sources.isEmpty());
        return sources;
    }

    private static final class Run {

        private final int status;
        private final String out;
        private final String err;

        private Run(int status, String out, String err) {
            this.status = status;
            this.out = out;
            this.err = err;
        }

    }

}
