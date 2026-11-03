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
 * messages. This starter table checks the module-shape rules and the -Werror
 * half of that contract. The full corpus table follows when the expression
 * rules are in place.
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
        new Case("unused", false, true,
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
            "dead() -> ok.")
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
