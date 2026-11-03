package org.jerlang.compiler.scan;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.jerlang.compiler.TestOtp;
import org.jerlang.erts.etf.ExternalTermReader;
import org.jerlang.type.Atom;
import org.jerlang.type.Term;
import org.jerlang.type.Tuple;
import org.junit.Assume;
import org.junit.Test;

/**
 * = The scanner's oracle
 *
 * Every token of every checked-in Erlang source is compared with
 * erl_scan:string/3 on the same source. This is the asymmetric check the plan
 * requires: the scanner is not tested against its own output, and a change in
 * one rule shows up as a token diff at a known source position.
 *
 * The comparison is term for term, so it also proves the categories and the
 * line annotations, not just the values. A scanner that got the syntax right
 * and reported every token on line one would fail here.
 */
public class ScannerOtpTest {

    @Test
    public void tokensMatchErlScan() throws Throwable {
        Path escript = TestOtp.escript();
        Assume.assumeTrue("no Erlang installation found; set ERL to erl.exe or escript.exe",
            escript != null);

        List<Path> sources = sources();
        Path directory = Files.createTempDirectory("scan-oracle");
        Path output = Files.createDirectories(directory.resolve("tokens"));
        List<String> paths = sources.stream()
            .map(path -> path.toAbsolutePath().toString().replace('\\', '/'))
            .collect(Collectors.toList());
        Path list = directory.resolve("files.txt");
        Files.write(list, String.join("\n", paths).getBytes(StandardCharsets.UTF_8));

        ProcessBuilder builder = new ProcessBuilder(
            escript.toString(),
            Paths.get("src/test/resources/otp/scan_tokens.escript").toAbsolutePath().toString(),
            list.toAbsolutePath().toString(),
            output.toAbsolutePath().toString());
        builder.redirectErrorStream(true);
        Process process = builder.start();
        String printed = new String(process.getInputStream().readAllBytes(),
            StandardCharsets.UTF_8);
        assertTrue("escript did not finish: " + printed, process.waitFor(120, TimeUnit.SECONDS));
        System.out.println(printed);
        assertTrue(printed, printed.contains("files " + sources.size()));
        assertEquals(printed, 0, process.exitValue());

        for (int number = 0; number < sources.size(); number++) {
            Path source = sources.get(number);
            Term result = new ExternalTermReader(
                Files.readAllBytes(output.resolve((number + 1) + ".etf"))).read();
            Tuple answer = result.toTuple();
            assertEquals(source + ": erl_scan refused it: " + result,
                Atom.of("ok"), answer.element(1));

            List<Term> expected = elements(answer.element(2));
            List<Token> actual = Scanner.scan(
                new String(Files.readAllBytes(source), StandardCharsets.UTF_8));
            assertEquals(source + ": token count", expected.size(), actual.size());
            for (int position = 0; position < expected.size(); position++) {
                assertEquals(source + ": token " + position,
                    expected.get(position), actual.get(position).toTerm());
            }
        }
    }

    /**
     * The sources the project already checked in, both the ones compiled into
     * fixtures and the larger set under src/test/erl. They are all valid
     * Erlang, so the scanner has no excuse for a disagreement.
     */
    private static List<Path> sources() throws Exception {
        List<Path> sources = new ArrayList<>();
        add(sources, Paths.get("src/test/resources"));
        add(sources, Paths.get("src/test/erl"));
        sources.sort(null);
        return sources;
    }

    private static void add(List<Path> sources, Path root) throws Exception {
        try (Stream<Path> walk = Files.walk(root)) {
            sources.addAll(walk
                .filter(Files::isRegularFile)
                .filter(path -> path.toString().endsWith(".erl"))
                .collect(Collectors.toList()));
        }
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

}
