package org.jerlang.jvm;

import static org.junit.Assert.assertEquals;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;

import org.jerlang.erts.Erlang;
import org.jerlang.type.Atom;
import org.jerlang.type.Binary;
import org.jerlang.type.BitString;
import org.jerlang.type.Integer;
import org.jerlang.type.List;
import org.jerlang.type.Term;
import org.jerlang.type.Tuple;
import org.jerlang.vm.VirtualMachine;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/**
 * Runs binary_match.beam, compiled by OTP 29, which uses bs_start_match3,
 * bs_match and its command list, bs_get_position, bs_set_position and
 * bs_get_tail.
 */
public class BinaryMatchTest {

    private static final Path SOURCE = Paths.get("src/test/resources/binary_match.beam");
    private static final Path LOCAL = Paths.get("binary_match.beam");

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
    public void testOneByteAndBinaryTail() throws Throwable {
        assertEquals(Tuple.of(Integer.of(1), Binary.of(2, 3)),
            apply("one", Binary.of(1, 2, 3)));
        assertEquals(Tuple.of(Integer.of(42), Binary.of()),
            apply("one", Binary.of(42)));
    }

    @Test
    public void testSkipFixedPrefix() throws Throwable {
        assertEquals(Binary.of(3, 4), apply("rest", Binary.of(1, 2, 3, 4)));
    }

    @Test
    public void testBigAndLittleEndianIntegers() throws Throwable {
        Binary input = Binary.of(0x00, 0x01, 0xFE, 0xFF, 0xFF, 0xFF, 0x07);
        assertEquals(Tuple.of(Integer.of(1), Integer.of(-2), Integer.of(7)),
            apply("ints", input));
    }

    @Test
    public void testLiteralPrefix() throws Throwable {
        assertEquals(Binary.of(9), apply("lit", Binary.of(1, 2, 9)));
        assertEquals(Atom.of("no"), apply("lit", Binary.of(1, 3)));
        assertEquals(Atom.of("no"), apply("lit", Atom.of("not_a_binary")));
    }

    @Test
    public void testPrefixRestAndWholeBinary() throws Throwable {
        Binary input = Binary.of(1, 2, 3, 4, 5, 6);
        assertEquals(Tuple.of(Binary.of(1, 2, 3, 4), Binary.of(5, 6), input),
            apply("tail", input));
    }

    @Test
    public void testBitLevelMatching() throws Throwable {
        // 101 10001 00001001
        assertEquals(Tuple.of(Integer.of(5), Integer.of(17), Binary.of(9)),
            apply("bits", Binary.of(0xB1, 9)));

        BitString input = new BitString(new int[] {0xB1, 0x90}, 4);
        assertEquals(Tuple.of(Integer.of(5), Integer.of(17),
            new BitString(new int[] {0x90}, 4)), apply("bits", input));
    }

    @Test
    public void testWholeBinaryCaptureUsesSavedPosition() throws Throwable {
        Binary input = Binary.of(1, 2, 3);
        assertEquals(Tuple.of(Integer.of(1), Integer.of(2), input),
            apply("nested", input));
    }

    @Test
    public void testGuardMakesTheCompilerUseStartMatch4() throws Throwable {
        assertEquals(Integer.of(7), apply("guarded", Binary.of(7)));
        assertEquals(Integer.of(9), apply("guarded", Binary.of(0, 9)));
        assertEquals(Atom.of("none"), apply("guarded", Binary.of(1, 2, 3)));
    }

    @Test
    public void testFailedMatchesFallThrough() throws Throwable {
        assertEquals(Tuple.of(Atom.of("ok"), Binary.of(3)),
            apply("short", Binary.of(1, 2, 3)));
        assertEquals(Atom.of("short"), apply("short", Binary.of(1)));
        assertEquals(Atom.of("short"), apply("short", Atom.of("not_a_binary")));
    }

    private Term apply(String function, Term argument) throws Throwable {
        return Erlang.apply(Atom.of("binary_match"), Atom.of(function),
            List.of(argument));
    }

}
