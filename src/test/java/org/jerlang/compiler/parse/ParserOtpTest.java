package org.jerlang.compiler.parse;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.jerlang.compiler.TestOtp;
import org.jerlang.compiler.preprocess.PreprocessException;
import org.jerlang.compiler.preprocess.Preprocessor;
import org.jerlang.erts.etf.ExternalTermReader;
import org.jerlang.type.Atom;
import org.jerlang.type.Term;
import org.jerlang.type.Tuple;
import org.junit.Assume;
import org.junit.Test;

/**
 * = The parser's oracle
 *
 * Every form is compared with epp:parse_file/2 on OTP 29, term for term. The
 * input is the preprocessor's token stream, so this test also depends on the
 * line and file annotations that the previous oracle established.
 *
 * The checked-in corpus is small enough for the inner loop. Set
 * -Djerlang.otp.lib=<erl>/lib to run the same comparison over every source in
 * an OTP installation; that is the A3 exit test, and it is kept out of the
 * default run because it needs an OTP tree and takes minutes.
 */
public class ParserOtpTest {

    /**
     * Files JErlang refuses and epp accepts. The key is the path as reported
     * by {@link #key(Path)} and the value is a stable reason. An entry is a
     * gap with an ID, not a pass; the exit count reports it separately.
     */
    private static final Map<String, String> SKIPPED = new LinkedHashMap<>();

    static {
        // No known corpus gaps.
    }

    @Test
    public void formsMatchEpp() throws Throwable {
        Path escript = TestOtp.escript();
        Assume.assumeTrue("no Erlang installation found; set ERL to erl.exe or escript.exe",
            escript != null);

        List<Path> sources = sources();
        Path directory = Files.createTempDirectory("parse-oracle");
        Path output = Files.createDirectories(directory.resolve("forms"));
        List<String> paths = sources.stream()
            .map(path -> path.toAbsolutePath().toString().replace('\\', '/'))
            .collect(Collectors.toList());
        Path list = directory.resolve("files.txt");
        Files.write(list, String.join("\n", paths).getBytes(StandardCharsets.UTF_8));

        ProcessBuilder builder = new ProcessBuilder(
            escript.toString(),
            Paths.get("src/test/resources/otp/parse_forms.escript").toAbsolutePath().toString(),
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

        int ok = 0;
        int skipped = 0;
        for (int number = 0; number < sources.size(); number++) {
            Path source = sources.get(number);
            String key = key(source);
            Term result = new ExternalTermReader(
                Files.readAllBytes(output.resolve((number + 1) + ".etf"))).read();
            Tuple answer = result.toTuple();
            if (Atom.of("error").equals(answer.element(1))) {
                assertTrue(source + ": epp refused it (" + result + ") without a skip entry",
                    SKIPPED.containsKey(key));
                skipped++;
                continue;
            }

            List<Term> expected = elements(answer.element(2));
            List<Term> actual;
            try {
                actual = Parser.parseForms(Preprocessor.scanFile(source));
            } catch (PreprocessException e) {
                fail(source + ": " + e.getMessage());
                return;
            } catch (RuntimeException e) {
                fail(source + ": " + e.getMessage());
                return;
            }
            assertEquals(source + ": form count", expected.size(), actual.size());
            for (int form = 0; form < expected.size(); form++) {
                assertEquals(source + ": form " + form, expected.get(form), actual.get(form));
            }
            ok++;
        }
        System.out.println("parse forms " + sources.size() + ", ok " + ok
            + ", skipped " + skipped);
    }

    private static String key(Path source) {
        return source.toString().replace('\\', '/');
    }

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
