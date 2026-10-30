package org.jerlang.jvm;

import static org.junit.Assert.assertEquals;

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
import org.jerlang.type.Term;
import org.jerlang.vm.VirtualMachine;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/**
 * Runs format_demo.beam, compiled from src/test/erl/format_demo.erl by
 * Erlang/OTP 29, against the built-in io, io_lib, unicode and code modules.
 *
 * Every expected string in the module was taken from the same call on OTP 29,
 * so a failure here is a difference in output, not a preference.
 */
public class FormatTest {

    private static final Path SOURCE = Paths.get("src/test/resources/format_demo.beam");
    private static final Path LOCAL = Paths.get("format_demo.beam");
    private static final Atom FORMAT_DEMO = Atom.of("format_demo");

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
    public void testTermsPrintLikeOtp() throws Throwable {
        assertEquals(Atom.of("ok"), apply("terms"));
    }

    @Test
    public void testStringsAndCharacters() throws Throwable {
        assertEquals(Atom.of("ok"), apply("strings"));
    }

    @Test
    public void testFieldWidthPrecisionAndPadding() throws Throwable {
        assertEquals(Atom.of("ok"), apply("widths"));
    }

    @Test
    public void testNumbers() throws Throwable {
        assertEquals(Atom.of("ok"), apply("numbers"));
    }

    @Test
    public void testNewlineTildeIgnoreAndBadFormat() throws Throwable {
        assertEquals(Atom.of("ok"), apply("misc"));
    }

    @Test
    public void testCharactersToBinary() throws Throwable {
        assertEquals(Atom.of("ok"), apply("unicode_binary"));
    }

    @Test
    public void testCharactersToList() throws Throwable {
        assertEquals(Atom.of("ok"), apply("unicode_list"));
    }

    @Test
    public void testUnicodeErrors() throws Throwable {
        assertEquals(Atom.of("ok"), apply("unicode_errors"));
    }

    @Test
    public void testEnsureLoaded() throws Throwable {
        assertEquals(Atom.of("ok"), apply("ensure_loaded"));
    }

    @Test
    public void testPrintablePredicatesAndMapToList() throws Throwable {
        assertEquals(Atom.of("ok"), apply("printable"));
    }

    /**
     * io:format writes to the standard output without adding a newline of its
     * own, so the captured text is exactly what the format strings produce.
     */
    @Test
    public void testIoFormatWritesToStandardOutput() throws Throwable {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        PrintStream original = System.out;
        System.setOut(new PrintStream(bytes, true, StandardCharsets.UTF_8));
        try {
            assertEquals(Atom.of("ok"), apply("io_format"));
        } finally {
            System.setOut(original);
        }
        assertEquals("hello world\nplain\n42\n", bytes.toString(StandardCharsets.UTF_8));
    }

    private Term apply(String function) throws Throwable {
        return Erlang.apply(FORMAT_DEMO, Atom.of(function), List.nil);
    }

}
