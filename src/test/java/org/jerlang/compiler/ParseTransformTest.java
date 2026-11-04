package org.jerlang.compiler;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Collections;

import org.jerlang.CodePaths;
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
 * = A7 exit test
 *
 * The transform itself is compiled by JErlang, not by `erlc`. The subject is
 * then compiled through the command with `-pa` naming the directory that holds
 * the transform's beam; the command has to load and run the transform, and the
 * resulting beam has to contain the rewritten function.
 */
public class ParseTransformTest {

    @Before
    public void startVirtualMachine() {
        VirtualMachine.instance().start();
    }

    @After
    public void stop() {
        VirtualMachine.instance().shutdown();
    }

    @Test
    public void transformCompiledByJerlangRewritesSubject() throws Throwable {
        Path root = Files.createTempDirectory("a7-transform");
        Path transformDirectory = Files.createDirectories(root.resolve("transform"));

        Path transformSource = writeTransform(transformDirectory,
            "a7_rewrite_transform", 1, 2);

        Compiler.Result transform = Compiler.compileFile(transformSource,
            Compiler.Options.builder()
                .outputDirectory(transformDirectory)
                .build());
        assertTrue(transform.diagnostics().toString(), transform.success());

        Path subjectSource = root.resolve("a7_subject.erl");
        Files.write(subjectSource, Arrays.asList(
            "-module(a7_subject).",
            "-export([value/0]).",
            "-compile([{parse_transform, a7_rewrite_transform}]).",
            "value() -> 1."));

        Path output = root.resolve("out");
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        int status = CompilerCommand.run(new String[] {
            "-o", output.toString(),
            "-pa", transformDirectory.toString(),
            subjectSource.toString()
        }, new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8),
            new PrintStream(err, true, StandardCharsets.UTF_8));
        assertEquals(err.toString(StandardCharsets.UTF_8), 0, status);
        assertTrue(Files.isRegularFile(output.resolve("a7_subject.beam")));

        Module module = load("a7_subject", output.resolve("a7_subject.beam"));
        try {
            assertEquals(Integer.of(2), apply(module, "value", 0));
        } finally {
            ModuleRegistry.unregister(Atom.of("a7_subject"));
        }
    }

    @Test
    public void transformsRunInCompileOrder() throws Throwable {
        Path root = Files.createTempDirectory("a7-order");
        Path transformDirectory = Files.createDirectories(root.resolve("transform"));
        Compiler.Options transformOptions = Compiler.Options.builder()
            .outputDirectory(transformDirectory)
            .build();
        assertTrue(Compiler.compileFile(writeTransform(transformDirectory,
            "a7_rewrite_one", 1, 2), transformOptions).success());
        assertTrue(Compiler.compileFile(writeTransform(transformDirectory,
            "a7_rewrite_two", 2, 3), transformOptions).success());

        Path subjectSource = root.resolve("a7_order_subject.erl");
        Files.write(subjectSource, Arrays.asList(
            "-module(a7_order_subject).",
            "-export([value/0]).",
            "-compile([{parse_transform, a7_rewrite_one},",
            "           {parse_transform, a7_rewrite_two}]).",
            "value() -> 1."));

        Path output = root.resolve("out");
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        int status = CompilerCommand.run(new String[] {
            "-o", output.toString(),
            "-pa", transformDirectory.toString(),
            subjectSource.toString()
        }, new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8),
            new PrintStream(err, true, StandardCharsets.UTF_8));
        assertEquals(err.toString(StandardCharsets.UTF_8), 0, status);

        Module module = load("a7_order_subject", output.resolve("a7_order_subject.beam"));
        try {
            assertEquals(Integer.of(3), apply(module, "value", 0));
        } finally {
            ModuleRegistry.unregister(Atom.of("a7_order_subject"));
        }
    }

    @Test
    public void compilerStartsAndStopsVmForATransform() throws Exception {
        VirtualMachine.instance().shutdown();
        Path root = Files.createTempDirectory("a7-lifecycle");
        Path transformDirectory = Files.createDirectories(root.resolve("transform"));
        Compiler.Options transformOptions = Compiler.Options.builder()
            .outputDirectory(transformDirectory)
            .build();
        assertTrue(Compiler.compileFile(writeTransform(transformDirectory,
            "a7_lifecycle_transform", 1, 2), transformOptions).success());

        Path subjectSource = root.resolve("a7_lifecycle_subject.erl");
        Files.write(subjectSource, Arrays.asList(
            "-module(a7_lifecycle_subject).",
            "-export([value/0]).",
            "-compile({parse_transform, a7_lifecycle_transform}).",
            "value() -> 1."));

        Path output = root.resolve("out");
        Compiler.Result result = Compiler.compileFile(subjectSource,
            Compiler.Options.builder()
                .outputDirectory(output)
                .libraryPath(transformDirectory.toString())
                .build());
        assertTrue(result.diagnostics().toString(), result.success());
        assertEquals(0L, VirtualMachine.instance().startedAtMillis());
        assertFalse(CodePaths.get().contains(
            CodePaths.normalizePath(transformDirectory.toString())));
    }

    @Test
    public void transformSeesOptionsAndLoadsHelperFromPa() throws Throwable {
        Path root = Files.createTempDirectory("a7-options");
        Path transformDirectory = Files.createDirectories(root.resolve("transform"));
        Compiler.Options transformOptions = Compiler.Options.builder()
            .outputDirectory(transformDirectory)
            .build();
        assertTrue(Compiler.compileFile(writeHelper(transformDirectory),
            transformOptions).success());
        assertTrue(Compiler.compileFile(writeOptionTransform(transformDirectory),
            transformOptions).success());

        Path subjectSource = root.resolve("a7_option_subject.erl");
        Files.write(subjectSource, Arrays.asList(
            "-module(a7_option_subject).",
            "-export([value/0]).",
            "-compile({parse_transform, a7_option_transform}).",
            "value() -> 1."));

        Path output = root.resolve("out");
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        int status = CompilerCommand.run(new String[] {
            "-o", output.toString(),
            "-pa", transformDirectory.toString(),
            "-Dflag=2",
            subjectSource.toString()
        }, new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8),
            new PrintStream(err, true, StandardCharsets.UTF_8));
        assertEquals(err.toString(StandardCharsets.UTF_8), 0, status);

        Module module = load("a7_option_subject", output.resolve("a7_option_subject.beam"));
        try {
            assertEquals(Integer.of(2), apply(module, "value", 0));
        } finally {
            ModuleRegistry.unregister(Atom.of("a7_option_subject"));
            ModuleRegistry.unregister(Atom.of("a7_transform_helper"));
        }
    }

    @Test
    public void transformTimeoutFailsWithoutWritingABeam() throws Exception {
        Path root = Files.createTempDirectory("a7-timeout");
        Path transformDirectory = Files.createDirectories(root.resolve("transform"));
        Compiler.Result transform = Compiler.compileFile(writeSlowTransform(transformDirectory),
            Compiler.Options.builder().outputDirectory(transformDirectory).build());
        assertTrue(transform.diagnostics().toString(), transform.success());

        Path subjectSource = root.resolve("a7_timeout_subject.erl");
        Files.write(subjectSource, Arrays.asList(
            "-module(a7_timeout_subject).",
            "-export([value/0]).",
            "-compile({parse_transform, a7_slow_transform}).",
            "value() -> 1."));

        Path output = root.resolve("out");
        Compiler.Result result = Compiler.compileFile(subjectSource,
            Compiler.Options.builder()
                .outputDirectory(output)
                .libraryPath(transformDirectory.toString())
                .parseTransformTimeoutMillis(200)
                .build());

        assertFalse(result.success());
        assertNull(result.beam());
        assertFalse(Files.exists(output.resolve("a7_timeout_subject.beam")));
        assertTrue(result.diagnostics().toString(),
            result.diagnostics().get(0).message().contains("timed out"));
    }

    @Test
    public void malformedTransformOutputFailsWithoutWritingABeam() throws Exception {
        Path root = Files.createTempDirectory("a7-malformed");
        Path transformDirectory = Files.createDirectories(root.resolve("transform"));
        Compiler.Result transform = Compiler.compileFile(writeBadTransform(transformDirectory),
            Compiler.Options.builder().outputDirectory(transformDirectory).build());
        assertTrue(transform.diagnostics().toString(), transform.success());

        Path source = root.resolve("a7_malformed_subject.erl");
        Files.write(source, Arrays.asList(
            "-module(a7_malformed_subject).",
            "-export([value/0]).",
            "-compile({parse_transform, a7_bad_transform}).",
            "value() -> 1."));

        Path output = root.resolve("out");
        Compiler.Result result = Compiler.compileFile(source,
            Compiler.Options.builder()
                .outputDirectory(output)
                .libraryPath(transformDirectory.toString())
                .build());

        assertFalse(result.success());
        assertFalse(Files.exists(output.resolve("a7_malformed_subject.beam")));
        assertTrue(result.diagnostics().toString(),
            result.diagnostics().get(0).message().contains("non-form"));
    }

    @Test
    public void missingTransformFailsWithoutWritingABeam() throws Exception {
        Path root = Files.createTempDirectory("a7-missing");
        Path source = root.resolve("a7_missing_subject.erl");
        Files.write(source, Arrays.asList(
            "-module(a7_missing_subject).",
            "-export([value/0]).",
            "-compile({parse_transform, a7_definitely_missing_transform}).",
            "value() -> 1."));

        Path output = root.resolve("out");
        Compiler.Result result = Compiler.compileFile(source,
            Compiler.Options.builder()
                .outputDirectory(output)
                .libraryPath(root.toString())
                .build());

        assertFalse(result.success());
        assertNull(result.beam());
        assertFalse(Files.exists(output.resolve("a7_missing_subject.beam")));
        assertTrue(result.diagnostics().toString(),
            result.diagnostics().get(0).message().contains("a7_definitely_missing_transform"));
    }

    private static Path writeHelper(Path directory) throws IOException {
        Path source = directory.resolve("a7_transform_helper.erl");
        Files.write(source, Arrays.asList(
            "-module(a7_transform_helper).",
            "-export([rewrite/1]).",
            "rewrite([]) ->",
            "    [];",
            "rewrite([Form | Rest]) ->",
            "    [rewrite_one(Form) | rewrite(Rest)].",
            "rewrite_one({function, Line, value, 0,",
            "             [{clause, ClauseLine, [], [], [{integer, BodyLine, 1}]}]}) ->",
            "    {function, Line, value, 0,",
            "     [{clause, ClauseLine, [], [], [{integer, BodyLine, 2}]}]};",
            "rewrite_one(Form) ->",
            "    Form."));
        return source;
    }

    private static Path writeOptionTransform(Path directory) throws IOException {
        Path source = directory.resolve("a7_option_transform.erl");
        Files.write(source, Arrays.asList(
            "-module(a7_option_transform).",
            "-export([parse_transform/2]).",
            "parse_transform(Forms, Options) ->",
            "    has_flag(Forms, Options).",
            "has_flag(Forms, [{d, flag, 2} | _]) ->",
            "    a7_transform_helper:rewrite(Forms);",
            "has_flag(Forms, [_ | Rest]) ->",
            "    has_flag(Forms, Rest);",
            "has_flag(Forms, []) ->",
            "    Forms."));
        return source;
    }

    private static Path writeBadTransform(Path directory) throws IOException {
        Path source = directory.resolve("a7_bad_transform.erl");
        Files.write(source, Arrays.asList(
            "-module(a7_bad_transform).",
            "-export([parse_transform/2]).",
            "parse_transform(_Forms, _Options) ->",
            "    [not_a_form]."));
        return source;
    }

    private static Path writeSlowTransform(Path directory) throws IOException {
        Path source = directory.resolve("a7_slow_transform.erl");
        Files.write(source, Arrays.asList(
            "-module(a7_slow_transform).",
            "-export([parse_transform/2]).",
            "parse_transform(_Forms, _Options) ->",
            "    slow().",
            "slow() ->",
            "    slow()."));
        return source;
    }

    private static Path writeTransform(Path directory, String module, int from, int to)
            throws IOException {
        Path source = directory.resolve(module + ".erl");
        Files.write(source, Arrays.asList(
            "-module(" + module + ").",
            "-export([parse_transform/2]).",
            "parse_transform([], _Options) ->",
            "    [];",
            "parse_transform([Form | Rest], Options) ->",
            "    [rewrite(Form) | parse_transform(Rest, Options)].",
            "rewrite({function, Line, value, 0,",
            "         [{clause, ClauseLine, [], [], [{integer, BodyLine, " + from + "}]}]}) ->",
            "    {function, Line, value, 0,",
            "     [{clause, ClauseLine, [], [], [{integer, BodyLine, " + to + "}]}]};",
            "rewrite(Form) ->",
            "    Form."));
        return source;
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

}
