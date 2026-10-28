package org.jerlang.jvm;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;

import org.jerlang.erts.Erlang;
import org.jerlang.type.Atom;
import org.jerlang.type.List;
import org.jerlang.vm.VirtualMachine;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/**
 * Runs crash_demo.beam, compiled from src/test/erl/crash_demo.erl by
 * Erlang/OTP 29, and checks the error report an uncaught exception prints.
 */
public class CrashReportTest {

    private static final Path SOURCE = Paths.get("src/test/resources/crash_demo.beam");
    private static final Path LOCAL = Paths.get("crash_demo.beam");

    private String stdout;
    private String stderr;

    @Before
    public void start() throws Exception {
        Files.copy(SOURCE, LOCAL, StandardCopyOption.REPLACE_EXISTING);
        VirtualMachine.instance().start();
    }

    @After
    public void stop() throws Exception {
        VirtualMachine.instance().shutdown();
        Files.deleteIfExists(LOCAL);
    }

    @Test
    public void anUncaughtErrorPrintsTheReportWithItsStackFrame() throws Throwable {
        run("crash");

        assertTrue(stdout, stdout.matches(
            "(?s)=ERROR REPORT==== \\d{4}-\\d{2}-\\d{2}::\\d{2}:\\d{2}:\\d{2}\\.\\d{6} ===\n"
                + "Error in process <0\\.\\d+\\.\\d+> with exit value:\n.*"));
        assertTrue(stdout, stdout.contains("{{badmatch,2},"
            + "[{crash_demo,crash_now,1,[{file,\"crash_demo.erl\"},{line,18}]}]}"));
        assertEquals(1, occurrences(stdout, "=ERROR REPORT===="));
        assertFalse(stderr, stderr.contains("=ERROR REPORT===="));
    }

    @Test
    public void theStacktraceContainsEveryCallerWithLineNumbers() throws Throwable {
        run("nested_crash");

        assertTrue(stdout, stdout.contains("{inner_boom,["
            + "{crash_demo,inner,1,[{file,\"crash_demo.erl\"},{line,33}]},"
            + "{crash_demo,outer,1,[{file,\"crash_demo.erl\"},{line,27}]}]}"));
    }

    @Test
    public void anUncaughtThrowIsReportedAsNocatch() throws Throwable {
        run("throw_crash");

        assertTrue(stdout, stdout.contains("{{nocatch,oops},"
            + "[{crash_demo,throw_now,0,[{file,\"crash_demo.erl\"},{line,42}]}]}"));
    }

    @Test
    public void anUncaughtExitIsNotACrash() throws Throwable {
        run("exit_crash");

        assertFalse(stdout, stdout.contains("=ERROR REPORT===="));
        assertFalse(stdout, stdout.contains("with exit value:"));
    }

    @Test
    public void aCaughtErrorPrintsNothing() throws Throwable {
        run("caught");

        assertFalse(stdout, stdout.contains("=ERROR REPORT===="));
        assertFalse(stdout, stdout.contains("with exit value:"));
    }

    private void run(String function) throws Throwable {
        PrintStream originalOut = System.out;
        PrintStream originalErr = System.err;
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        PrintStream capturedOut = new PrintStream(out, true, "UTF-8");
        PrintStream capturedErr = new PrintStream(err, true, "UTF-8");
        System.setOut(capturedOut);
        System.setErr(capturedErr);
        try {
            Erlang.apply(Atom.of("crash_demo"), Atom.of(function), List.nil);
        } finally {
            capturedOut.flush();
            capturedErr.flush();
            System.setOut(originalOut);
            System.setErr(originalErr);
            stdout = new String(out.toByteArray(), StandardCharsets.UTF_8)
                .replace("\r\n", "\n");
            stderr = new String(err.toByteArray(), StandardCharsets.UTF_8)
                .replace("\r\n", "\n");
        }
    }

    private static int occurrences(String text, String needle) {
        int count = 0;
        int index = text.indexOf(needle);
        while (index >= 0) {
            count++;
            index = text.indexOf(needle, index + needle.length());
        }
        return count;
    }

}
