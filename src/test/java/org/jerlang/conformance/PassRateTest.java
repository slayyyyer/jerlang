package org.jerlang.conformance;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;

import org.junit.Assume;
import org.junit.Test;

/**
 * The conformance pass rate.
 *
 * The checked-in corpus is the gate: every module in it must load and use only
 * handled opcodes, so a regression in the loader or the opcode table fails
 * here. That corpus is small and written for this runtime, though, so it can
 * not answer "how much real Erlang does this run?".
 *
 * The larger corpus is a directory of real BEAM files, usually an OTP
 * installation. It needs OTP present, so it is opt-in like the live OTP
 * tests:
 *
 * <pre>
 * mvn -Dtest=PassRateTest \
 *     -Djerlang.conformance.dir="$OTP/lib" test
 * </pre>
 *
 * The directory may be a path list, and is searched recursively. The run
 * prints the rate; it only asserts a floor when
 * {@code -Djerlang.conformance.min=<0..1>} is given, because the exact number
 * moves with the OTP version being measured.
 */
public class PassRateTest {

    private static final Path CORPUS = Paths.get("src/test/resources/corpus");
    private static final Path OVERRIDE = Paths.get("src/test/resources/override");

    @Test
    public void testCheckedInCorpusPasses() throws Exception {
        ConformanceReport.Report report = ConformanceReport.inspect(CORPUS, OVERRIDE);

        assertTrue("the corpus must contain modules", report.total() >= 5);
        assertEquals(report.render(), 0, report.count(ConformanceReport.Status.LOAD_FAILED));
        assertEquals(report.render(), 0, report.count(ConformanceReport.Status.OPCODE_GAP));
        assertEquals(report.render(), 1.0, report.passRate(), 0.0);
    }

    @Test
    public void testLargerCorpusPassRate() throws Exception {
        String directory = System.getProperty("jerlang.conformance.dir");
        if (directory == null || directory.isEmpty()) {
            directory = System.getenv("JERLANG_CONFORMANCE_DIR");
        }
        Assume.assumeTrue("set -Djerlang.conformance.dir or JERLANG_CONFORMANCE_DIR "
            + "to a directory of BEAM files, normally an OTP installation",
            directory != null && !directory.isEmpty());

        java.util.List<Path> roots = new ArrayList<>();
        for (String part : directory.split(java.util.regex.Pattern.quote(File.pathSeparator))) {
            if (!part.isEmpty()) {
                roots.add(Paths.get(part));
            }
        }
        ConformanceReport.Report report = ConformanceReport.inspect(roots);

        System.out.println(report.render());
        System.out.println("CONFORMANCE " + report.summary());
        assertTrue("the corpus must contain modules", report.total() > 0);

        String floor = System.getProperty("jerlang.conformance.min");
        if (floor != null && !floor.isEmpty()) {
            double minimum = Double.parseDouble(floor);
            // A floor, not an equality: a newer OTP patch may add modules
            // that lift the rate, and that must not fail the build.
            assertTrue("pass rate " + report.passRate() + " is below " + minimum
                + "\n" + report.render(), report.passRate() >= minimum);
        }
    }

}
