package org.jerlang.compiler.lint;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

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

    @Test
    public void unboundVariableIsAnError() throws IOException {
        List<Diagnostic> found = lint(
            "-module(sample).",
            "-export([f/1]).",
            "f(X) -> X + Y.");

        assertEquals(1, found.size());
        assertTrue(found.get(0).isError());
        assertEquals(3, found.get(0).line());
        assertEquals("variable 'Y' is unbound", found.get(0).message());
    }

    @Test
    public void unsafeCaseBindingIsAnError() throws IOException {
        List<Diagnostic> found = lint(
            "-module(sample).",
            "-export([f/1]).",
            "f(X) -> case X of a -> Y = 1; b -> ok end, Y.");

        assertEquals(1, found.size());
        assertTrue(found.get(0).isError());
        assertEquals("variable 'Y' unsafe in 'case'", found.get(0).message());
    }

    @Test
    public void caseBindingInEveryClauseIsSafe() throws IOException {
        List<Diagnostic> found = lint(
            "-module(sample).",
            "-export([f/1]).",
            "f(X) -> case X of a -> Y = 1; b -> Y = 2 end, Y.");

        assertTrue(found.toString(), found.isEmpty());
    }

    @Test
    public void unsafeTryBindingIsAnError() throws IOException {
        List<Diagnostic> found = lint(
            "-module(sample).",
            "-export([f/1]).",
            "f(X) -> try Y = X of _ -> ok catch _:_ -> ok end, Y.");

        assertEquals(1, found.size());
        assertTrue(found.get(0).isError());
        assertEquals("variable 'Y' unsafe in 'try'", found.get(0).message());
    }

    @Test
    public void catchBindingIsUnsafe() throws IOException {
        List<Diagnostic> found = lint(
            "-module(sample).",
            "-export([f/0]).",
            "f() -> catch (Y = 1), Y.");

        assertTrue(found.toString(), found.stream().anyMatch(
            diagnostic -> diagnostic.isError()
                && "variable 'Y' unsafe in 'catch'".equals(
                    diagnostic.message())));
    }

    @Test
    public void maybeBindingIsUnsafeAfterTheExpression() throws IOException {
        List<Diagnostic> found = lint(
            "-module(sample).",
            "-export([f/1]).",
            "f(X) -> maybe Y = X ?= X, Y end, Y.");

        assertTrue(found.toString(), found.stream().anyMatch(
            diagnostic -> diagnostic.isError()
                && "variable 'Y' unsafe in 'maybe'".equals(
                    diagnostic.message())));
    }

    @Test
    public void illegalGuardExpressionIsAnError() throws IOException {
        List<Diagnostic> found = lint(
            "-module(sample).",
            "-export([f/1]).",
            "f(X) when X = 1 -> ok.");

        assertTrue(found.toString(), found.stream().anyMatch(
            diagnostic -> diagnostic.isError()
                && "illegal guard expression".equals(diagnostic.message())));
    }

    @Test
    public void localCallInGuardIsIllegal() throws IOException {
        List<Diagnostic> found = lint(
            "-module(sample).",
            "-export([f/1]).",
            "f(X) when g(X) -> ok.",
            "g(_) -> true.");

        assertTrue(found.toString(), found.stream().anyMatch(
            diagnostic -> diagnostic.isError()
                && "call to local/imported function g/1 is illegal in guard"
                    .equals(diagnostic.message())));
    }

    @Test
    public void guardBifIsAllowed() throws IOException {
        List<Diagnostic> found = lint(
            "-module(sample).",
            "-export([f/1]).",
            "f(X) when length(X) > 0 -> ok.");

        assertTrue(found.toString(), found.isEmpty());
    }

    @Test
    public void guardTypeTestIsAcceptedButWarns() throws IOException {
        List<Diagnostic> found = lint(
            "-module(sample).",
            "-export([f/1]).",
            "f(P) when port(P) -> ok.");

        assertEquals(1, found.size());
        assertFalse(found.get(0).isError());
        assertEquals("port/1 as a type test is obsolete and will be removed"
            + " in OTP 30; use is_port/1 instead.", found.get(0).message());
    }

    @Test
    public void mapTypeTestIsAnIllegalGuard() throws IOException {
        List<Diagnostic> found = lint(
            "-module(sample).",
            "-export([f/1]).",
            "f(M) when map(M) -> ok.");

        assertTrue(found.toString(), found.stream().anyMatch(
            diagnostic -> diagnostic.isError()
                && diagnostic.message().contains("illegal")));
    }

    @Test
    public void duplicateMapKeyWarns() throws IOException {
        List<Diagnostic> found = lint(
            "-module(sample).",
            "-export([f/0]).",
            "f() -> #{a => 1, a => 2}.");

        assertEquals(1, found.size());
        assertFalse(found.get(0).isError());
        assertEquals("key 'a' will be overridden in expression",
            found.get(0).message());
    }

    @Test
    public void exactMapKeyInConstructionIsAnError() throws IOException {
        List<Diagnostic> found = lint(
            "-module(sample).",
            "-export([f/0]).",
            "f() -> #{a := 1}.");

        assertEquals(1, found.size());
        assertTrue(found.get(0).isError());
        assertEquals("only association operators '=>' are allowed in map construction",
            found.get(0).message());
    }

    @Test
    public void duplicateMapPatternWarns() throws IOException {
        List<Diagnostic> found = lint(
            "-module(sample).",
            "-export([f/1]).",
            "f(#{a := 1, a := 2}) -> ok.");

        assertEquals(1, found.size());
        assertFalse(found.get(0).isError());
        assertEquals("pattern cannot possibly match", found.get(0).message());
    }

    @Test
    public void associationOperatorInMapPatternIsAnError() throws IOException {
        List<Diagnostic> found = lint(
            "-module(sample).",
            "-export([f/1]).",
            "f(#{a => 1}) -> ok.");

        assertEquals(1, found.size());
        assertTrue(found.get(0).isError());
        assertEquals("illegal pattern, did you mean to use `:=`?",
            found.get(0).message());
    }

    @Test
    public void mapUpdateAllowsExactKey() throws IOException {
        List<Diagnostic> found = lint(
            "-module(sample).",
            "-export([f/1]).",
            "f(M) -> M#{a := 1}.");

        assertTrue(found.toString(), found.isEmpty());
    }

    @Test
    public void mapUpdateDuplicateKeyWarns() throws IOException {
        List<Diagnostic> found = lint(
            "-module(sample).",
            "-export([f/1]).",
            "f(M) -> M#{a => 1, a => 2}.");

        assertEquals(1, found.size());
        assertEquals("key 'a' will be overridden in expression",
            found.get(0).message());
    }

    @Test
    public void binaryFieldWithoutSizeMustBeLast() throws IOException {
        List<Diagnostic> found = lint(
            "-module(sample).",
            "-export([f/1]).",
            "f(<<X/binary, Y:8>>) -> {X, Y}.");

        assertEquals(1, found.size());
        assertTrue(found.get(0).isError());
        assertEquals("a binary field without size is only allowed at the end"
            + " of a binary pattern", found.get(0).message());
    }

    @Test
    public void binarySizeMustBeBound() throws IOException {
        List<Diagnostic> found = lint(
            "-module(sample).",
            "-export([f/1]).",
            "f(<<X:N>>) -> X.");

        assertEquals(1, found.size());
        assertTrue(found.get(0).isError());
        assertEquals("variable 'N' is unbound", found.get(0).message());
    }

    @Test
    public void unusedVariableWarns() throws IOException {
        List<Diagnostic> found = lint(
            "-module(sample).",
            "-export([f/1]).",
            "f(X) -> Y = 1, X.");

        assertEquals(1, found.size());
        assertFalse(found.get(0).isError());
        assertEquals("variable 'Y' is unused", found.get(0).message());
    }

    @Test
    public void underscoreVariableDoesNotWarn() throws IOException {
        List<Diagnostic> found = lint(
            "-module(sample).",
            "-export([f/1]).",
            "f(_X) -> ok.");

        assertTrue(found.toString(), found.isEmpty());
    }

    @Test
    public void funShadowWarns() throws IOException {
        List<Diagnostic> found = lint(
            "-module(sample).",
            "-export([f/1]).",
            "f(X) -> fun(X) -> X end.");

        assertTrue(found.toString(), found.stream().anyMatch(
            diagnostic -> "variable 'X' shadowed in 'fun'".equals(
                diagnostic.message())));
    }

    @Test
    public void generatorShadowWarns() throws IOException {
        List<Diagnostic> found = lint(
            "-module(sample).",
            "-export([f/2]).",
            "f(X, L) -> [X || X <- L].");

        assertTrue(found.toString(), found.stream().anyMatch(
            diagnostic -> "variable 'X' shadowed in 'generate'".equals(
                diagnostic.message())));
    }

    @Test
    public void undefinedLocalCallIsAnError() throws IOException {
        List<Diagnostic> found = lint(
            "-module(sample).",
            "-export([f/0]).",
            "f() -> g().");

        assertEquals(1, found.size());
        assertTrue(found.get(0).isError());
        assertEquals("function g/0 undefined", found.get(0).message());
    }

    @Test
    public void undefinedFunReferenceIsAnError() throws IOException {
        List<Diagnostic> found = lint(
            "-module(sample).",
            "-export([f/0]).",
            "f() -> fun g/0.");

        assertEquals(1, found.size());
        assertTrue(found.get(0).isError());
        assertEquals("function g/0 undefined", found.get(0).message());
    }

    @Test
    public void onLoadWrongArityIsAnError() throws IOException {
        List<Diagnostic> found = lint(
            "-module(sample).",
            "-export([f/0]).",
            "-on_load(init/1).",
            "f() -> ok.",
            "init(X) -> X.");

        assertEquals(1, found.size());
        assertTrue(found.get(0).isError());
        assertEquals("function init/1 has wrong arity (must be 0)",
            found.get(0).message());
    }

    @Test
    public void onLoadMissingIsAnError() throws IOException {
        List<Diagnostic> found = lint(
            "-module(sample).",
            "-export([f/0]).",
            "-on_load(init/0).",
            "f() -> ok.");

        assertEquals(1, found.size());
        assertTrue(found.get(0).isError());
        assertEquals("function init/0 undefined", found.get(0).message());
    }

    @Test
    public void duplicateExportWarns() throws IOException {
        List<Diagnostic> found = lint(
            "-module(sample).",
            "-export([f/0, f/0]).",
            "f() -> ok.");

        assertEquals(1, found.size());
        assertFalse(found.get(0).isError());
        assertEquals("function f/0 already exported", found.get(0).message());
    }

    @Test
    public void importAllowsLocalCall() throws IOException {
        List<Diagnostic> found = lint(
            "-module(sample).",
            "-import(lists, [member/2]).",
            "-export([f/1]).",
            "f(X) -> member(X, []).");

        assertTrue(found.toString(), found.isEmpty());
    }

    @Test
    public void nowarnUnusedVariableSuppressesTheWarning() throws IOException {
        List<Diagnostic> found = lint(
            "-module(sample).",
            "-compile(nowarn_unused_vars).",
            "-export([f/1]).",
            "f(X) -> Y = 1, X.");

        assertTrue(found.toString(), found.isEmpty());
    }

    @Test
    public void nowarnShadowSuppressesOnlyTheShadowWarning() throws IOException {
        List<Diagnostic> found = lint(
            "-module(sample).",
            "-compile(nowarn_shadow_vars).",
            "-export([f/1]).",
            "f(X) -> fun(X) -> X end.");

        assertEquals(1, found.size());
        assertEquals("variable 'X' is unused", found.get(0).message());
    }

    @Test
    public void comprehensionFilterMayBindForTheResult() throws IOException {
        List<Diagnostic> found = lint(
            "-module(sample).",
            "-export([f/1]).",
            "f(L) ->",
            "    [Y || X <- L,",
            "          case X of a -> Y = 1; _ -> Y = 2 end].");

        assertTrue(found.toString(), found.isEmpty());
    }

    @Test
    public void constantExpressionIsALegalPattern() throws IOException {
        List<Diagnostic> found = lint(
            "-module(sample).",
            "-export([f/1]).",
            "f(1 bsl 4) -> ok.");

        assertTrue(found.toString(), found.isEmpty());
    }

    @Test
    public void generatedModuleInfoIsNotUndefined() throws IOException {
        List<Diagnostic> found = lint(
            "-module(sample).",
            "-export([f/0]).",
            "f() -> module_info(compile).");

        assertTrue(found.toString(), found.isEmpty());
    }

    @Test
    public void recordInfoIsNotUndefined() throws IOException {
        List<Diagnostic> found = lint(
            "-module(sample).",
            "-record(r, {a}).",
            "-export([f/0]).",
            "f() -> record_info(fields, r).");

        assertTrue(found.toString(), found.isEmpty());
    }

    private static List<Diagnostic> lint(String... source) throws IOException {
        Path directory = Files.createTempDirectory("lint-test");
        Path file = directory.resolve("sample.erl");
        Files.write(file, String.join("\n", source).getBytes(UTF_8));
        return Linter.lint(file);
    }

}
