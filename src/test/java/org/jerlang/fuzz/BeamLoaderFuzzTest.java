package org.jerlang.fuzz;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Random;

import org.jerlang.Module;
import org.jerlang.ModuleLoader;
import org.jerlang.type.Atom;
import org.junit.Assume;
import org.junit.Test;

/**
 * Fuzzes the BEAM loader with mutated copies of a checked-in module.
 *
 * The contract the loader has to keep: for any byte sequence, return a module
 * or refuse it, without hanging, without exhausting memory and without leaving
 * the caller to deal with a JVM-level error. A mutated file that loads is fine
 * (chunk checksums are not verified), and so is a refusal; a crash is a finding.
 *
 * The loader already catches Throwable and reports it, which is why the counts
 * here are of loads against refusals: the fuzzer's job is to prove that no
 * mutation makes it hang or die, and the summary prints every distinct refusal
 * reason so a systematic gap is visible.
 */
public class BeamLoaderFuzzTest {

    @Test
    public void mutatedModulesAreLoadedOrRefused() throws Exception {
        Path fixture = Paths.get("src/test/resources/constructs_demo.beam");
        Assume.assumeTrue("missing " + fixture, Files.isRegularFile(fixture));
        byte[] original = Files.readAllBytes(fixture);
        assertTrue("the fixture is too small to mutate", original.length > 64);

        int iterations = FuzzSupport.iterations();
        Random random = new Random(FuzzSupport.seed());
        Map<String, Integer> refusals = new LinkedHashMap<>();
        int loaded = 0;
        int refused = 0;
        long startedAt = System.currentTimeMillis();

        for (int iteration = 0; iteration < iterations; iteration++) {
            byte[] mutated = FuzzSupport.mutate(original, random);
            Path file = Files.createTempFile("jerlang-fuzz", ".beam");
            try {
                Files.write(file, mutated);
                try {
                    Module module = ModuleLoader.load(Atom.of("constructs_demo"), file.toFile());
                    if (module == null) {
                        refused++;
                        refusals.merge("refused", 1, java.lang.Integer::sum);
                    } else {
                        loaded++;
                        // Loading has to produce something the interpreter can
                        // call: an empty export table would be a silent refusal
                        // dressed up as success.
                        assertTrue("iteration " + iteration + " loaded without exports",
                            !module.beamData().exportTableChunk().exports().isEmpty());
                    }
                } catch (Throwable failure) {
                    FuzzSupport.assertNotFatal(failure,
                        "iteration " + iteration + " (seed " + FuzzSupport.seed() + ")");
                    refused++;
                    refusals.merge(FuzzSupport.describe(failure), 1, java.lang.Integer::sum);
                }
            } finally {
                Files.deleteIfExists(file);
            }
        }

        long elapsed = System.currentTimeMillis() - startedAt;
        System.out.printf(Locale.ROOT,
            "FUZZ beam loader: %d mutations in %d ms, %d loaded, %d refused%n",
            iterations, elapsed, loaded, refused);
        refusals.forEach((reason, count) ->
            System.out.println("  " + reason + ": " + count));

        assertEquals("every mutation must be counted", iterations, loaded + refused);
    }

}
