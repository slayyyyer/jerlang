package org.jerlang.compiler;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Collections;

import org.junit.Test;

/**
 * = Compiler failure contract
 *
 * These tests pin down the difference between the two public entry points.
 * {@link Compiler#compile(Path, Compiler.Options)} reports a compile failure by
 * throwing {@link Compiler.CompileException}; {@code compileFile} returns a
 * failed {@link Compiler.Result} for the same source and never leaves a partial
 * beam behind.
 */
public class CompilerApiTest {

    @Test
    public void missingSourceIsReturnedAsAFailedResult() throws Exception {
        Path root = Files.createTempDirectory("compiler-api-missing");
        Compiler.Result result = Compiler.compileFile(root.resolve("missing.erl"),
            Compiler.Options.builder().outputDirectory(root.resolve("out")).build());

        assertFalse(result.success());
        assertNull(result.beam());
        assertFalse(result.diagnostics().isEmpty());
        assertTrue(result.diagnostics().toString(),
            result.diagnostics().get(0).message().contains("missing.erl"));
    }

    @Test
    public void compileThrowsForTheSameMissingSource() throws Exception {
        Path root = Files.createTempDirectory("compiler-api-throws");
        try {
            Compiler.compile(root.resolve("missing.erl"),
                Compiler.Options.builder().outputDirectory(root).build());
            fail("compile should throw CompileException");
        } catch (Compiler.CompileException error) {
            assertFalse(error.diagnostics().isEmpty());
            assertTrue(error.getMessage(), error.getMessage().contains("missing.erl"));
        }
    }

    @Test
    public void parseAndModuleErrorsAreFailedResults() throws Exception {
        Path root = Files.createTempDirectory("compiler-api-errors");
        Path output = root.resolve("out");
        Path parse = root.resolve("parse_error.erl");
        Files.write(parse, Collections.singletonList("-module(parse_error). value( -> 1."));

        Compiler.Result parsed = Compiler.compileFile(parse,
            Compiler.Options.builder().outputDirectory(output).build());
        assertFalse(parsed.success());
        assertFalse(parsed.diagnostics().isEmpty());
        assertFalse(Files.exists(output.resolve("parse_error.beam")));

        Path noModule = root.resolve("no_module.erl");
        Files.write(noModule, Arrays.asList(
            "-export([value/0]).",
            "value() -> 1."));
        Compiler.Result module = Compiler.compileFile(noModule,
            Compiler.Options.builder().outputDirectory(output).build());
        assertFalse(module.success());
        assertTrue(module.diagnostics().toString(),
            module.diagnostics().get(0).message().contains("no -module"));
    }

    @Test
    public void failedCompileDoesNotReplaceAnExistingBeam() throws Exception {
        Path root = Files.createTempDirectory("compiler-api-atomic");
        Path source = root.resolve("keep.erl");
        Files.write(source, Arrays.asList(
            "-module(keep).",
            "-export([value/0]).",
            "value() -> 1."));
        Path output = root.resolve("out");
        Compiler.Result good = Compiler.compileFile(source,
            Compiler.Options.builder().outputDirectory(output).build());
        assertTrue(good.diagnostics().toString(), good.success());
        byte[] before = Files.readAllBytes(good.beam());

        Files.write(source, Collections.singletonList("-module(keep). value( -> 1."),
            StandardCharsets.UTF_8);
        Compiler.Result bad = Compiler.compileFile(source,
            Compiler.Options.builder().outputDirectory(output).build());

        assertFalse(bad.success());
        assertArrayEquals(before, Files.readAllBytes(output.resolve("keep.beam")));
    }

}
