package org.jerlang.stdlib.beam_lib;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import org.junit.Test;

/**
 * The A2 oracle: bytes to text to bytes, compared with the file that came in,
 * and every listing compared with the one that is checked in.
 *
 * A disassembler that is only checked by assembling its own output proves that
 * the two halves agree with each other, which is the failure mode the plan
 * warns about. The corpus is the other half of the pair.
 */
public class DisassemblyRoundTripTest {

    @Test
    public void everyFixtureSurvivesTheTextForm() throws Exception {
        for (Path path : Fixtures.paths()) {
            byte[] original = Fixtures.bytes(path);
            byte[] rebuilt = TextAssembler.assemble(Disassembler.disassemble(original));
            assertArrayEquals(path.toString(), original, rebuilt);
        }
    }

    @Test
    public void everyDisassemblyMatchesItsGolden() throws Exception {
        for (Path path : Fixtures.paths()) {
            byte[] original = Fixtures.bytes(path);
            String text = Disassembler.disassemble(original);
            Path goldenPath = golden(path);
            assertTrue("missing golden: " + goldenPath, Files.exists(goldenPath));
            // The repository checks text out with the platform line ending, and
            // the disassembler always writes LF. Normalising here keeps the
            // goldens golden rather than Windows-only.
            String golden = new String(Files.readAllBytes(goldenPath),
                StandardCharsets.UTF_8).replace("\r\n", "\n");
            assertEquals(goldenPath.toString(), golden, text);
            assertArrayEquals(path.toString(), original, TextAssembler.assemble(golden));
        }
    }

    @Test
    public void atomsAndInstructionsAreNamedInTheText() throws Exception {
        String text = Disassembler.disassemble(
            Fixtures.bytes(Paths.get("src/test/resources/acceptance_demo.beam")));
        assertTrue(text, text.contains("atom 33 collect"));
        assertTrue(text, text.contains("ins 1 label"));
        assertTrue(text, text.contains("ins 3 int_code_end"));
    }

    private static Path golden(Path fixture) {
        String relative = Paths.get("src/test/resources").relativize(fixture).toString();
        return Paths.get("src/test/resources/disassembly",
            relative.replace('\\', '_').replace('/', '_') + ".txt");
    }

}
