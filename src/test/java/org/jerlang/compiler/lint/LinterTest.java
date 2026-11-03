package org.jerlang.compiler.lint;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.Test;

public class LinterTest {

    @Test
    public void undefinedExportIsAnError() throws IOException {
        List<Diagnostic> found = lint(
            "-module(sample).",
            "-export([f/0, missing/1]).",
            "f() -> ok.");

        assertEquals(1, found.size());
        assertTrue(found.get(0).isError());
        assertEquals(2, found.get(0).line());
        assertEquals("function missing/1 undefined", found.get(0).message());
    }

    @Test
    public void duplicateDefinitionIsAnError() throws IOException {
        List<Diagnostic> found = lint(
            "-module(sample).",
            "-export([f/0]).",
            "f() -> ok.",
            "f() -> ok.");

        assertEquals(1, found.size());
        assertTrue(found.get(0).isError());
        assertEquals(4, found.get(0).line());
        assertEquals("function f/0 already defined", found.get(0).message());
    }

    @Test
    public void uncalledLocalFunctionIsAWarning() throws IOException {
        List<Diagnostic> found = lint(
            "-module(sample).",
            "-export([f/0]).",
            "f() -> ok.",
            "g() -> ok.");

        assertEquals(1, found.size());
        assertFalse(found.get(0).isError());
        assertEquals(4, found.get(0).line());
        assertEquals("function g/0 is unused", found.get(0).message());
    }

    @Test
    public void calledLocalFunctionIsUsed() throws IOException {
        List<Diagnostic> found = lint(
            "-module(sample).",
            "-export([f/0]).",
            "f() -> g().",
            "g() -> ok.");

        assertTrue(found.isEmpty());
    }

    @Test
    public void funReferenceCountsAsUse() throws IOException {
        List<Diagnostic> found = lint(
            "-module(sample).",
            "-export([f/0]).",
            "f() -> fun g/0.",
            "g() -> ok.");

        assertTrue(found.isEmpty());
    }

    @Test
    public void onLoadCountsAsUse() throws IOException {
        List<Diagnostic> found = lint(
            "-module(sample).",
            "-export([f/0]).",
            "-on_load(init/0).",
            "f() -> ok.",
            "init() -> ok.");

        assertTrue(found.isEmpty());
    }

    @Test
    public void exportAllWarnsAndSuppressesUnusedWarnings() throws IOException {
        List<Diagnostic> found = lint(
            "-module(sample).",
            "-compile(export_all).",
            "f() -> ok.",
            "g() -> ok.");

        assertEquals(1, found.size());
        assertFalse(found.get(0).isError());
        assertEquals(2, found.get(0).line());
        assertEquals("export_all flag enabled - all functions will be exported",
            found.get(0).message());
    }

    @Test
    public void diagnosticsAreOrderedByLine() throws IOException {
        List<Diagnostic> found = lint(
            "-module(sample).",
            "-export([f/0, missing/1]).",
            "f() -> ok.",
            "g() -> ok.");

        assertEquals(2, found.size());
        assertEquals(2, found.get(0).line());
        assertEquals(4, found.get(1).line());
    }

    private static List<Diagnostic> lint(String... source) throws IOException {
        Path directory = Files.createTempDirectory("lint-test");
        Path file = directory.resolve("sample.erl");
        Files.write(file, String.join("\n", source).getBytes(UTF_8));
        return Linter.lint(file);
    }

}
