package org.jerlang.stdlib.beam_lib;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import org.junit.Assume;
import org.junit.Test;

/**
 * = The OTP load check
 *
 * Byte identity says the bytes are the ones a compiler wrote. It does not say
 * the erts loader accepts them: the loader runs a validator over every
 * instruction, checks labels, registers and stack depth, and a file that is a
 * perfect copy of a broken one would pass the digest test.
 *
 * So this test hands every assembled fixture to a real Erlang. The original
 * file is the control, so a fixture OTP 29 refuses for its own reasons is
 * reported rather than counted as a defect.
 *
 * The check skips when there is no Erlang. Set {@code ERL} to the full path of
 * {@code erl.exe} (or {@code escript.exe}); {@code ERLANG_HOME} and
 * {@code PATH} are searched too, and on Windows the installer's default
 * directory is the last resort.
 */
public class OtpLoadTest {

    @Test
    public void assembledFixturesAreLoadedByOtp() throws Exception {
        Path escript = escript();
        Assume.assumeTrue("no Erlang installation found; set ERL to erl.exe or escript.exe",
            escript != null);

        List<Path> fixtures = Fixtures.paths();
        Path directory = Files.createTempDirectory("otp-load");
        List<String> lines = new ArrayList<>(fixtures.size());
        for (int index = 0; index < fixtures.size(); index++) {
            byte[] original = Fixtures.bytes(fixtures.get(index));
            byte[] assembled = BeamAssembler.assemble(original);
            Path control = directory.resolve("original-" + index + ".beam");
            Path rewritten = directory.resolve("assembled-" + index + ".beam");
            Files.write(control, original);
            Files.write(rewritten, assembled);
            lines.add(path(control) + "\t" + path(rewritten));
        }
        Path list = directory.resolve("files.txt");
        Files.write(list, String.join("\n", lines).getBytes(StandardCharsets.UTF_8));

        Path script = Paths.get("src/test/resources/otp/verify_load.escript").toAbsolutePath();
        ProcessBuilder builder = new ProcessBuilder(escript.toString(), script.toString(),
            list.toAbsolutePath().toString());
        // A loading module runs its on_load function. Keeping the working
        // directory inside the temporary one means a fixture that writes a
        // file does it there instead of in the repository.
        builder.directory(directory.toFile());
        builder.redirectErrorStream(true);
        Process process = builder.start();
        String output = new String(process.getInputStream().readAllBytes(),
            StandardCharsets.UTF_8);
        assertTrue("escript did not finish: " + output, process.waitFor(180, TimeUnit.SECONDS));

        System.out.println(output);
        assertTrue(output, output.contains("files " + fixtures.size()));
        assertEquals(output, 0, process.exitValue());
        assertTrue(output, output.contains("failures 0"));
    }

    private static String path(Path path) {
        return path.toAbsolutePath().toString().replace('\\', '/');
    }

    /**
     * The escript next to erl, so one environment variable identifies the
     * installation rather than three.
     */
    private static Path escript() {
        Path erl = erl();
        if (erl == null) {
            return null;
        }
        Path escript = erl.resolveSibling(erl.getFileName().toString()
            .replace("erl.exe", "escript.exe").replace("erl", "escript"));
        return Files.isRegularFile(escript) ? escript : null;
    }

    private static Path erl() {
        Path configured = usable(System.getenv("ERL"));
        if (configured != null) {
            return configured;
        }
        String home = System.getenv("ERLANG_HOME");
        if (home != null) {
            Path erl = Paths.get(home, "bin", "erl.exe");
            if (Files.isRegularFile(erl)) {
                return erl;
            }
        }
        String path = System.getenv("PATH");
        if (path != null) {
            for (String directory : path.split(File.pathSeparator)) {
                Path erl = Paths.get(directory, "erl.exe");
                if (Files.isRegularFile(erl)) {
                    return erl;
                }
            }
        }
        Path installed = Paths.get("C:/Program Files/Erlang OTP/bin/erl.exe");
        return Files.isRegularFile(installed) ? installed : null;
    }

    private static Path usable(String configured) {
        if (configured == null || configured.isEmpty()) {
            return null;
        }
        Path path = Paths.get(configured);
        return Files.isRegularFile(path) ? path : null;
    }

}
