package org.jerlang.compiler.lint;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.jerlang.compiler.TestOtp;
import org.junit.Assume;
import org.junit.Test;

/**
 * = The A4 exit test
 *
 * Every .erl under an OTP lib tree that A3 can parse is linted, and the
 * accepted/rejected verdict is compared with erlc. Sources that OTP itself
 * cannot preprocess are the same named skips as the A3 exit test; they are
 * counted separately, never treated as passes.
 *
 * This is opt-in for the same reason as the parser corpus: it needs an OTP
 * installation and takes minutes. Point -Djerlang.otp.lib at a lib directory,
 * or pass true to use the one belonging to ERL.
 *
 *   mvn -Djerlang.otp.lib=true -Dtest=LinterLibOtpTest test
 */
public class LinterLibOtpTest {

    private static final Path SKIP_FILE =
        Paths.get("src/test/resources/otp/parse_skips.txt");

    @Test
    public void everyLintVerdictMatchesErlc() throws Throwable {
        Path lib = lib();
        Assume.assumeTrue("set -Djerlang.otp.lib=true or to an OTP lib directory",
            lib != null);
        Path erlc = TestOtp.erlc();
        Assume.assumeTrue("no erlc executable found; set ERL to erl.exe",
            erlc != null);

        List<Path> sources = new ArrayList<>();
        try (Stream<Path> walk = Files.walk(lib)) {
            sources.addAll(walk
                .filter(Files::isRegularFile)
                .filter(path -> path.toString().endsWith(".erl"))
                .collect(Collectors.toList()));
        }
        sources.sort(null);
        Map<String, String> skipped = skips();
        System.out.println("otp lib lint " + lib + " sources " + sources.size()
            + " skip entries " + skipped.size());

        int ignored = 0;
        List<Callable<Outcome>> tasks = new ArrayList<>();
        for (Path source : sources) {
            String key = key(lib, source);
            if (skipped.containsKey(key)) {
                ignored++;
                continue;
            }
            tasks.add(() -> inspect(erlc, lib, source));
        }

        int ok = 0;
        int accepted = 0;
        int rejected = 0;
        List<String> failures = new ArrayList<>();
        ExecutorService executor = Executors.newFixedThreadPool(4);
        List<Future<Outcome>> results;
        try {
            results = executor.invokeAll(tasks);
        } finally {
            executor.shutdownNow();
        }
        for (Future<Outcome> future : results) {
            Outcome outcome = future.get();
            if (outcome.failure != null) {
                failures.add(outcome.key + ": " + outcome.failure);
                continue;
            }

            boolean erlcError = outcome.result.exit != 0;
            if (outcome.lintError != erlcError) {
                StringBuilder detail = new StringBuilder(outcome.key)
                    .append(": linter ")
                    .append(outcome.lintError ? "rejected" : "accepted")
                    .append(", erlc ")
                    .append(erlcError ? "rejected" : "accepted")
                    .append(" (exit ").append(outcome.result.exit).append(')');
                outcome.diagnostics.stream().limit(4).forEach(diagnostic ->
                    detail.append(" | ").append(diagnostic.format()));
                detail.append(" | erlc: ")
                    .append(abbreviate(outcome.result.output));
                failures.add(detail.toString());
            } else if (erlcError) {
                rejected++;
                ok++;
            } else {
                accepted++;
                ok++;
            }
        }

        for (int index = 0; index < Math.min(40, failures.size()); index++) {
            System.out.println("FAIL " + failures.get(index));
        }
        Files.write(Paths.get("target/a4-lint-failures.txt"), failures, UTF_8);
        System.out.println("otp lib lint " + sources.size() + ", ok " + ok
            + ", accepted " + accepted + ", rejected " + rejected
            + ", skipped " + ignored + ", failed " + failures.size());
        assertEquals(failures.isEmpty() ? "" : failures.get(0), 0,
            failures.size());
    }

    private static final class Outcome {

        private final String key;
        private final boolean lintError;
        private final List<Diagnostic> diagnostics;
        private final CompileResult result;
        private final String failure;

        private Outcome(String key, boolean lintError,
            List<Diagnostic> diagnostics, CompileResult result, String failure) {
            this.key = key;
            this.lintError = lintError;
            this.diagnostics = diagnostics;
            this.result = result;
            this.failure = failure;
        }

    }

    private static Outcome inspect(Path erlc, Path lib, Path source) {
        String key = key(lib, source);
        try {
            List<String> includes = includes(source);
            List<Diagnostic> diagnostics = Linter.lint(source, includes,
                Collections.singletonList(lib.toString()), 29);
            boolean lintError = diagnostics.stream()
                .anyMatch(Diagnostic::isError);
            return new Outcome(key, lintError, diagnostics,
                compile(erlc, source, includes), null);
        } catch (Throwable failure) {
            return new Outcome(key, false, Collections.emptyList(), null,
                "linter/compiler threw " + failure.getClass().getSimpleName()
                    + ": " + failure.getMessage());
        }
    }

    private static final class CompileResult {

        private final int exit;
        private final String output;

        private CompileResult(int exit, String output) {
            this.exit = exit;
            this.output = output;
        }

    }

    private static CompileResult compile(Path erlc, Path source,
        List<String> includes) throws Exception {
        Path directory = Files.createTempDirectory("lint-lib-beam");
        Path output = Files.createDirectory(directory.resolve("out"));
        Path log = directory.resolve("erlc.txt");
        List<String> command = new ArrayList<>();
        command.add(erlc.toString());
        command.add("-o");
        command.add(output.toString());
        for (String include : includes) {
            command.add("-I");
            command.add(include);
        }
        command.add(source.toString());
        ProcessBuilder builder = new ProcessBuilder(command);
        builder.redirectErrorStream(true);
        builder.redirectOutput(log.toFile());
        Process process = builder.start();
        if (!process.waitFor(60, TimeUnit.SECONDS)) {
            process.destroyForcibly();
            return new CompileResult(-1, "timed out: " + command);
        }
        return new CompileResult(process.exitValue(), new String(
            Files.readAllBytes(log), UTF_8));
    }

    private static Path lib() {
        String configured = System.getProperty("jerlang.otp.lib");
        if (configured == null || configured.isEmpty()
            || configured.equals("false")) {
            return null;
        }
        Path path;
        if (configured.equals("true")) {
            Path erl = TestOtp.erl();
            if (erl == null) {
                return null;
            }
            path = erl.getParent().getParent().resolve("lib");
        } else {
            path = Paths.get(configured);
        }
        return Files.isDirectory(path) ? path : null;
    }

    private static List<String> includes(Path source) {
        List<String> includes = new ArrayList<>();
        includes.add(source.getParent().toString());
        Path app = source.getParent().getParent();
        Path appInclude = app.resolve("include");
        if (Files.isDirectory(appInclude)) {
            includes.add(appInclude.toString());
        }
        return includes;
    }

    private static Map<String, String> skips() throws Exception {
        Map<String, String> skip = new LinkedHashMap<>();
        if (!Files.isRegularFile(SKIP_FILE)) {
            return skip;
        }
        for (String line : Files.readAllLines(SKIP_FILE, UTF_8)) {
            String trimmed = line.trim();
            if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                continue;
            }
            String[] fields = trimmed.split("\\s+", 3);
            assertTrue("bad skip entry: " + line, fields.length == 3);
            skip.put(fields[1], fields[0] + " " + fields[2]);
        }
        return skip;
    }

    private static String key(Path lib, Path source) {
        return lib.relativize(source).toString().replace('\\', '/');
    }

    private static String abbreviate(String text) {
        String oneLine = text.replace('\r', ' ').replace('\n', ' ');
        return oneLine.length() <= 240 ? oneLine
            : oneLine.substring(0, 240) + "...";
    }

}
