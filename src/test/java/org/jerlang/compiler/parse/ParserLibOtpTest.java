package org.jerlang.compiler.parse;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.jerlang.compiler.TestOtp;
import org.jerlang.compiler.preprocess.Preprocessor;
import org.jerlang.erts.etf.ExternalTermReader;
import org.jerlang.type.Atom;
import org.jerlang.type.Term;
import org.jerlang.type.Tuple;
import org.junit.Assume;
import org.junit.Test;

/**
 * = The A3 exit test
 *
 * Every .erl under an OTP lib tree is parsed with epp + erl_parse and with
 * JErlang's preprocessor + parser. The comparison is term for term, including
 * the file and line annotations, because those are part of the abstract form.
 *
 * This is opt-in: it needs an OTP installation and takes minutes, so it is not
 * part of the default test run. Point -Djerlang.otp.lib at a lib directory, or
 * pass true to use the one belonging to ERL:
 *
 *   mvn -Djerlang.otp.lib=true -Dtest=ParserLibOtpTest test
 *
 * A source that OTP itself refuses, or that JErlang does not parse yet, is
 * listed in src/test/resources/otp/parse_skips.txt with a stable ID. That file
 * is a gap list, not a pass list: the report counts skipped sources separately.
 */
public class ParserLibOtpTest {

    private static final Path SKIP_FILE = Paths.get("src/test/resources/otp/parse_skips.txt");

    @Test
    public void everyLibSourceMatchesEpp() throws Throwable {
        Path lib = lib();
        Assume.assumeTrue("set -Djerlang.otp.lib=true or to an OTP lib directory",
            lib != null);

        List<Path> sources = new ArrayList<>();
        try (Stream<Path> walk = Files.walk(lib)) {
            sources.addAll(walk
                .filter(Files::isRegularFile)
                .filter(path -> path.toString().endsWith(".erl"))
                .collect(Collectors.toList()));
        }
        sources.sort(null);
        Map<String, String> skipped = skips();
        System.out.println("otp lib " + lib + " sources " + sources.size()
            + " skip entries " + skipped.size());

        Path directory = Files.createTempDirectory("parse-lib-oracle");
        Path output = Files.createDirectories(directory.resolve("forms"));
        List<String> paths = sources.stream()
            .map(path -> path.toAbsolutePath().toString().replace('\\', '/'))
            .collect(Collectors.toList());
        Path list = directory.resolve("files.txt");
        Files.write(list, String.join("\n", paths).getBytes(StandardCharsets.UTF_8));

        Path escript = TestOtp.escript();
        ProcessBuilder builder = new ProcessBuilder(
            escript.toString(),
            Paths.get("src/test/resources/otp/parse_forms_all.escript").toAbsolutePath().toString(),
            list.toAbsolutePath().toString(),
            output.toAbsolutePath().toString());
        builder.redirectErrorStream(true);
        Process process = builder.start();
        String printed = new String(process.getInputStream().readAllBytes(),
            StandardCharsets.UTF_8);
        assertTrue("escript did not finish: " + printed,
            process.waitFor(600, TimeUnit.SECONDS));
        assertTrue(printed, printed.contains("files " + sources.size()));
        assertEquals(printed, 0, process.exitValue());

        int ok = 0;
        int ignored = 0;
        List<String> failures = new ArrayList<>();
        for (int number = 0; number < sources.size(); number++) {
            Path source = sources.get(number);
            String key = key(lib, source);
            Term result = new ExternalTermReader(
                Files.readAllBytes(output.resolve((number + 1) + ".etf"))).read();
            Tuple answer = result.toTuple();
            if (Atom.of("error").equals(answer.element(1))) {
                if (skipped.containsKey(key)) {
                    ignored++;
                    continue;
                }
                failures.add(key + ": epp refused it: " + abbreviate(result));
                continue;
            }
            if (skipped.containsKey(key)) {
                ignored++;
                continue;
            }

            List<String> includes = new ArrayList<>();
            includes.add(source.getParent().toString());
            Path app = source.getParent().getParent();
            Path appInclude = app.resolve("include");
            if (Files.isDirectory(appInclude)) {
                includes.add(appInclude.toString());
            }
            List<Term> expected = elements(answer.element(2));
            List<Term> actual;
            try {
                actual = Parser.parseForms(Preprocessor.scanFile(source,
                    includes, Collections.singletonList(lib.toString()), 29));
            } catch (Throwable e) {
                String message = e.getClass().getName() + ": " + e.getMessage();
                StackTraceElement[] trace = e.getStackTrace();
                for (int frame = 0; frame < Math.min(4, trace.length); frame++) {
                    message += " | " + trace[frame];
                }
                failures.add(key + ": " + message);
                continue;
            }
            if (expected.size() != actual.size()) {
                failures.add(key + ": form count " + actual.size() + ", expected "
                    + expected.size());
                continue;
            }
            int bad = -1;
            String difference = null;
            for (int form = 0; form < expected.size(); form++) {
                if (!expected.get(form).equals(actual.get(form))) {
                    bad = form;
                    difference = difference(expected.get(form), actual.get(form));
                    break;
                }
            }
            if (bad >= 0) {
                failures.add(key + ": form " + bad + ": " + difference);
                continue;
            }
            ok++;
        }

        for (int index = 0; index < Math.min(40, failures.size()); index++) {
            System.out.println("FAIL " + failures.get(index));
        }
        Files.write(Paths.get("target/a3lib-failures.txt"), failures,
            StandardCharsets.UTF_8);
        System.out.println("otp lib parse " + sources.size() + ", ok " + ok
            + ", skipped " + ignored + ", failed " + failures.size());
        failures.stream()
            .collect(Collectors.groupingBy(ParserLibOtpTest::category,
                Collectors.counting()))
            .entrySet().stream()
            .sorted((left, right) -> Long.compare(right.getValue(), left.getValue()))
            .limit(12)
            .forEach(entry -> System.out.println("group " + entry.getValue()
                + " " + entry.getKey()));
        assertEquals(failures.isEmpty() ? "" : failures.get(0), 0, failures.size());
    }

    private static Path lib() {
        String configured = System.getProperty("jerlang.otp.lib");
        if (configured == null || configured.isEmpty() || configured.equals("false")) {
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

    private static Map<String, String> skips() throws Exception {
        Map<String, String> skip = new LinkedHashMap<>();
        if (!Files.isRegularFile(SKIP_FILE)) {
            return skip;
        }
        for (String line : Files.readAllLines(SKIP_FILE, StandardCharsets.UTF_8)) {
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

    private static List<Term> elements(Term term) {
        List<Term> elements = new ArrayList<>();
        org.jerlang.type.List list = term.toList();
        while (list != org.jerlang.type.List.nil) {
            elements.add(list.head());
            list = list.tail();
        }
        return elements;
    }

    private static String difference(Term expected, Term actual) {
        if (expected instanceof Tuple && actual instanceof Tuple) {
            Tuple left = (Tuple) expected;
            Tuple right = (Tuple) actual;
            if (left.arity() != right.arity()) {
                return "tuple arity " + right.arity() + ", expected " + left.arity();
            }
            for (int index = 1; index <= left.arity(); index++) {
                if (!left.element(index).equals(right.element(index))) {
                    return "element " + index + ": "
                        + difference(left.element(index), right.element(index));
                }
            }
        }
        if (expected instanceof org.jerlang.type.List
            && actual instanceof org.jerlang.type.List) {
            org.jerlang.type.List left = expected.toList();
            org.jerlang.type.List right = actual.toList();
            int index = 0;
            while (left != org.jerlang.type.List.nil
                && right != org.jerlang.type.List.nil) {
                if (!left.head().equals(right.head())) {
                    return "list element " + index + ": "
                        + difference(left.head(), right.head());
                }
                left = left.tail();
                right = right.tail();
                index++;
            }
            if (left != org.jerlang.type.List.nil || right != org.jerlang.type.List.nil) {
                return "list length differs at " + index;
            }
        }
        return "expected " + abbreviate(expected) + " but was " + abbreviate(actual);
    }

    private static String category(String failure) {
        int colon = failure.indexOf(": ");
        String message = colon < 0 ? failure : failure.substring(colon + 2);
        if (message.startsWith("epp refused")) {
            return "epp refused";
        }
        if (message.contains("undefined macro")) {
            return "undefined macro";
        }
        if (message.contains("already defined")) {
            return "macro redefinition";
        }
        if (message.contains("cannot normalise")) {
            return "cannot normalise";
        }
        if (message.contains("unexpected character")) {
            return "unexpected character";
        }
        if (message.contains("expected")) {
            return "parser expected";
        }
        if (message.contains("form ")) {
            return "form mismatch";
        }
        return message.length() > 60 ? message.substring(0, 60) : message;
    }

    private static String abbreviate(Term term) {
        String text = term.toString();
        return text.length() <= 240 ? text : text.substring(0, 240) + "...";
    }

}
