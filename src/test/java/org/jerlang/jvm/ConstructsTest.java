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
import org.jerlang.type.Float;
import org.jerlang.type.ImproperList;
import org.jerlang.type.Integer;
import org.jerlang.type.List;
import org.jerlang.type.Term;
import org.jerlang.type.Tuple;
import org.jerlang.vm.VirtualMachine;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/**
 * Runs constructs_demo.beam, compiled by OTP 29, which uses bs_create_bin,
 * update_record, badrecord, get_hd and get_tl.
 */
public class ConstructsTest {

    private static final Path SOURCE = Paths.get("src/test/resources/constructs_demo.beam");
    private static final Path LOCAL = Paths.get("constructs_demo.beam");
    private static final Atom MODULE = Atom.of("constructs_demo");

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
    public void testBinaryConstruction() throws Throwable {
        assertEquals(Binary.of(1, 0, 7, 255),
            apply("bin", Integer.of(7), Binary.of(255)));
        assertEquals(Binary.of(1, 0, 0),
            apply("bin", Integer.of(0), Binary.of()));
    }

    @Test
    public void testIntegerFloatAndStringSegments() throws Throwable {
        // 32 bit little endian signed -2, 64 bit float 1.5, then "hi".
        assertEquals(Binary.of(254, 255, 255, 255,
                63, 248, 0, 0, 0, 0, 0, 0, 104, 105),
            apply("nums", Integer.of(-2), Float.of(1.5)));
    }

    @Test
    public void testUtfSegments() throws Throwable {
        assertEquals(Binary.of(65, 0, 65, 0, 0, 0, 65), apply("utf", Integer.of(65)));
        // Euro sign: two byte UTF-8, one UTF-16 code unit, one UTF-32 unit.
        assertEquals(Binary.of(0xE2, 0x82, 0xAC, 0x20, 0xAC, 0, 0, 0x20, 0xAC),
            apply("utf", Integer.of(0x20AC)));
        // Grinning face: four byte UTF-8 and a UTF-16 surrogate pair.
        assertEquals(Binary.of(0xF0, 0x9F, 0x98, 0x80,
                0xD8, 0x3D, 0xDE, 0x00, 0x00, 0x01, 0xF6, 0x00),
            apply("utf", Integer.of(0x1F600)));
    }

    @Test
    public void testAppendKeepsBits() throws Throwable {
        BitString bitstring = new BitString(new int[] {0x30}, 4);
        assertEquals(new BitString(new int[] {1, 2, 0x30}, 4),
            apply("build", Binary.of(1, 2), bitstring));
    }

    @Test
    public void testRecordConstructionAndUpdate() throws Throwable {
        Term record = apply("rec", Integer.of(1), Integer.of(3));
        assertEquals(Tuple.of(Atom.of("r"), Integer.of(1), Integer.of(2), Integer.of(3)),
            record);
        assertEquals(Tuple.of(Atom.of("r"), Integer.of(9), Integer.of(2), Integer.of(3)),
            apply("update", record, Integer.of(9)));
        assertEquals(Integer.of(2), apply("field", record));
    }

    @Test
    public void testRecordUpdateOfSomethingElseRaisesBadrecord() throws Throwable {
        Tuple notARecord = Tuple.of(Atom.of("wrong"), Integer.of(1), Integer.of(2));
        assertEquals(Tuple.of(Atom.of("error"),
                Tuple.of(Atom.of("badrecord"), notARecord)),
            apply("catch_update", notARecord, Integer.of(9)));
    }

    @Test
    public void testHeadAndTail() throws Throwable {
        assertEquals(Integer.of(1), apply("heads", List.of(Integer.of(1), Integer.of(2))));
        assertEquals(List.of(Integer.of(2)),
            apply("tails", List.of(Integer.of(1), Integer.of(2))));
        assertEquals(List.nil, apply("tails", List.of(Integer.of(1))));
    }

    @Test
    public void testHdAndTlAcceptAnImproperList() throws Throwable {
        // gen_server stores its timeout state as [TRef | TimeoutMsg] and
        // calls hd/1 and tl/1 on it, so both the BIFs and the list-matching
        // instructions must accept a non-list tail.
        ImproperList improper = new ImproperList(Atom.of("a"), Atom.of("b"));
        assertEquals(Atom.of("a"), apply("bif_head", improper));
        assertEquals(Atom.of("b"), apply("bif_tail", improper));
        assertEquals(Atom.of("a"), apply("heads_improper", improper));
        assertEquals(Atom.of("b"), apply("tails_improper", improper));
    }

    @Test
    public void testFailedPatternRaisesFunctionClause() throws Throwable {
        // A function with a single list clause jumps back to its entry label
        // when the pattern does not match; the interpreter turns that into
        // function_clause instead of re-entering the function forever.
        assertEquals(Tuple.of(Atom.of("error"), Atom.of("function_clause")),
            apply("catch_heads", Atom.of("not_a_list")));
        assertEquals(Tuple.of(Atom.of("error"), Atom.of("function_clause")),
            apply("catch_tails", List.nil));
    }

    @Test
    public void testFieldAccessOnANonRecordRaisesBadrecord() throws Throwable {
        Tuple notARecord = Tuple.of(Atom.of("wrong"), Integer.of(1), Integer.of(2));
        assertEquals(Tuple.of(Atom.of("error"),
                Tuple.of(Atom.of("badrecord"), notARecord)),
            apply("catch_field", notARecord));
    }

    @Test
    public void testRunExercisesEverything() throws Throwable {
        assertEquals(Atom.of("ok"), Erlang.apply(MODULE, Atom.of("run"), List.nil));
    }

    private Term apply(String function, Term... arguments) throws Throwable {
        return Erlang.apply(MODULE, Atom.of(function), List.of(arguments));
    }

}
