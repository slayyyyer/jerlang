package org.jerlang.compiler.lint;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;

import org.jerlang.compiler.TestOtp;
import org.junit.Assume;
import org.junit.Test;

/**
 * = The linter's oracle
 *
 * A4's exit test is erlc's accept/reject verdict, not a hand-written list of
 * messages. This table checks both halves: an error makes erlc exit 1, a
 * warning makes it exit 1 only under -Werror, and a clean source exits 0 in
 * both modes. The full corpus table is {@link LinterLibOtpTest}.
 */
public class LinterOtpTest {

    private static final class Case {

        private final String name;
        private final boolean errors;
        private final boolean warnings;
        private final String[] source;

        private Case(String name, boolean errors, boolean warnings,
            String... source) {
            this.name = name;
            this.errors = errors;
            this.warnings = warnings;
            this.source = source;
        }

    }

    private static final Case[] CASES = {
        new Case("clean", false, false,
            "-module(sample).",
            "-export([f/0]).",
            "f() -> ok."),
        new Case("undefined_export", true, false,
            "-module(sample).",
            "-export([f/0, missing/1]).",
            "f() -> ok."),
        new Case("duplicate", true, false,
            "-module(sample).",
            "-export([f/0]).",
            "f() -> ok.",
            "f() -> ok."),
        new Case("unused_function", false, true,
            "-module(sample).",
            "-export([f/0]).",
            "f() -> ok.",
            "g() -> ok."),
        new Case("export_all", false, true,
            "-module(sample).",
            "-compile(export_all).",
            "f() -> ok.",
            "g() -> ok."),
        new Case("on_load", false, true,
            "-module(sample).",
            "-export([f/0]).",
            "-on_load(init/0).",
            "f() -> ok.",
            "init() -> ok.",
            "dead() -> ok."),
        new Case("unbound", true, false,
            "-module(sample).",
            "-export([f/1]).",
            "f(X) -> X + Y."),
        new Case("unsafe_case", true, false,
            "-module(sample).",
            "-export([f/1]).",
            "f(X) -> case X of a -> Y = 1; b -> ok end, Y."),
        new Case("case_all_bind", false, false,
            "-module(sample).",
            "-export([f/1]).",
            "f(X) -> case X of a -> Y = 1; b -> Y = 2 end, Y."),
        new Case("illegal_guard", true, true,
            "-module(sample).",
            "-export([f/1]).",
            "f(X) when X = 1 -> ok."),
        new Case("local_guard_call", true, true,
            "-module(sample).",
            "-export([f/1]).",
            "f(X) when g(X) -> ok.",
            "g(_) -> true."),
        new Case("guard_bif", false, false,
            "-module(sample).",
            "-export([f/1]).",
            "f(X) when length(X) > 0 -> ok."),
        new Case("guard_type_test", false, true,
            "-module(sample).",
            "-export([f/1]).",
            "f(P) when port(P) -> ok."),
        new Case("illegal_type_test", true, false,
            "-module(sample).",
            "-export([f/1]).",
            "f(M) when map(M) -> ok."),
        new Case("guard_remote_bif", false, false,
            "-module(sample).",
            "-export([f/1]).",
            "f(X) when erlang:length(X) > 0 -> ok."),
        new Case("duplicate_map_key", false, true,
            "-module(sample).",
            "-export([f/0]).",
            "f() -> #{a => 1, a => 2}."),
        new Case("exact_map_construction", true, false,
            "-module(sample).",
            "-export([f/0]).",
            "f() -> #{a := 1}."),
        new Case("duplicate_map_pattern", false, true,
            "-module(sample).",
            "-export([f/1]).",
            "f(#{a := 1, a := 2}) -> ok."),
        new Case("assoc_map_pattern", true, false,
            "-module(sample).",
            "-export([f/1]).",
            "f(#{a => 1}) -> ok."),
        new Case("binary_no_size", true, false,
            "-module(sample).",
            "-export([f/1]).",
            "f(<<X/binary, Y:8>>) -> {X, Y}."),
        new Case("undefined_call", true, false,
            "-module(sample).",
            "-export([f/0]).",
            "f() -> g()."),
        new Case("unused_variable", false, true,
            "-module(sample).",
            "-export([f/1]).",
            "f(X) -> Y = 1, X."),
        new Case("fun_shadow", false, true,
            "-module(sample).",
            "-export([f/1]).",
            "f(X) -> fun(X) -> X end."),
        new Case("generator_shadow", false, true,
            "-module(sample).",
            "-export([f/2]).",
            "f(X, L) -> [X || X <- L]."),
        new Case("try_unsafe", true, false,
            "-module(sample).",
            "-export([f/1]).",
            "f(X) -> try Y = X of _ -> ok catch _:_ -> ok end, Y."),
        new Case("maybe_unsafe", true, false,
            "-module(sample).",
            "-export([f/1]).",
            "f(X) -> maybe Y = X ?= X, Y end, Y."),
        new Case("import", false, false,
            "-module(sample).",
            "-import(lists, [member/2]).",
            "-export([f/1]).",
            "f(X) -> member(X, [])."),
        new Case("nowarn_unused", false, false,
            "-module(sample).",
            "-compile(nowarn_unused_vars).",
            "-export([f/1]).",
            "f(X) -> Y = 1, X."),
        new Case("map_update_exact", false, false,
            "-module(sample).",
            "-export([f/1]).",
            "f(M) -> M#{a := 1}."),
        new Case("map_update_duplicate", false, true,
            "-module(sample).",
            "-export([f/1]).",
            "f(M) -> M#{a => 1, a => 2}."),
        new Case("comp_filter_bind", false, false,
            "-module(sample).",
            "-export([f/1]).",
            "f(L) ->",
            "    [Y || X <- L,",
            "          case X of a -> Y = 1; _ -> Y = 2 end]."),
        new Case("constant_pattern", false, false,
            "-module(sample).",
            "-export([f/1]).",
            "f(1 bsl 4) -> ok."),
        new Case("module_info", false, false,
            "-module(sample).",
            "-export([f/0]).",
            "f() -> module_info(compile)."),
        new Case("record_info", false, false,
            "-module(sample).",
            "-record(r, {a}).",
            "-export([f/0]).",
            "f() -> record_info(fields, r)."),
        new Case("on_load_bad_arity", true, false,
            "-module(sample).",
            "-export([f/0]).",
            "-on_load(init/1).",
            "f() -> ok.",
            "init(X) -> X."),
        new Case("on_load_missing", true, false,
            "-module(sample).",
            "-export([f/0]).",
            "-on_load(init/0).",
            "f() -> ok."),
        new Case("duplicate_export", false, true,
            "-module(sample).",
            "-export([f/0, f/0]).",
            "f() -> ok.")
    };

    @Test
    public void verdictsAndWerrorMatchErlc() throws Exception {
        Path erlc = TestOtp.erlc();
        Assume.assumeTrue("no erlc executable found; set ERL to erl.exe", erlc != null);

        for (Case test : CASES) {
            Path directory = Files.createTempDirectory("lint-oracle");
            Path source = directory.resolve("sample.erl");
            Files.write(source, Arrays.asList(test.source), UTF_8);

            List<Diagnostic> found = Linter.lint(source);
            boolean hasError = found.stream().anyMatch(Diagnostic::isError);
            boolean hasWarning = found.stream()
                .anyMatch(diagnostic -> !diagnostic.isError());

            assertEquals(test.name + " linter error", test.errors, hasError);
            assertEquals(test.name + " linter warning", test.warnings, hasWarning);
            assertEquals(test.name + " erlc", test.errors ? 1 : 0,
                compile(erlc, source, false));
            assertEquals(test.name + " erlc -Werror",
                test.errors || test.warnings ? 1 : 0,
                compile(erlc, source, true));
        }
    }

    private static int compile(Path erlc, Path source, boolean werror)
        throws IOException, InterruptedException {
        Path output = Files.createTempDirectory("lint-beam");
        java.util.List<String> command = new java.util.ArrayList<>();
        command.add(erlc.toString());
        if (werror) {
            command.add("-Werror");
        }
        command.add("-o");
        command.add(output.toString());
        command.add(source.toString());
        ProcessBuilder builder = new ProcessBuilder(command);
        builder.redirectErrorStream(true);
        Process process = builder.start();
        String printed = new String(process.getInputStream().readAllBytes(), UTF_8);
        assertTrue(printed, process.waitFor(30, TimeUnit.SECONDS));
        return process.exitValue();
    }

}
