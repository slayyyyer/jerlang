package org.jerlang.compiler;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * = Finding OTP
 *
 * Compiler tests use OTP as the oracle and skip when there is none, like the
 * assembler's load test. {@code ERL} is the one setting that identifies an
 * installation; {@code ERLANG_HOME}, {@code PATH} and the Windows installer's
 * default directory are the fallbacks.
 */
public final class TestOtp {

    private TestOtp() {
    }

    public static Path escript() {
        Path erl = erl();
        if (erl == null) {
            return null;
        }
        Path escript = erl.resolveSibling(erl.getFileName().toString()
            .replace("erl.exe", "escript.exe").replace("erl", "escript"));
        return Files.isRegularFile(escript) ? escript : null;
    }

    public static Path erlc() {
        Path erl = erl();
        if (erl == null) {
            return null;
        }
        Path erlc = erl.resolveSibling(erl.getFileName().toString()
            .replace("erl", "erlc"));
        return Files.isRegularFile(erlc) ? erlc : null;
    }

    public static Path erl() {
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
