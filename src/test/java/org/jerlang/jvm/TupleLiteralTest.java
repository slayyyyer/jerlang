package org.jerlang.jvm;

import static org.junit.Assert.assertEquals;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;

import org.jerlang.erts.Erlang;
import org.jerlang.type.Atom;
import org.jerlang.type.Integer;
import org.jerlang.type.List;
import org.jerlang.type.Term;
import org.jerlang.type.Tuple;
import org.jerlang.vm.VirtualMachine;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/**
 * Runs tuple_literal_demo.beam, compiled by OTP 29. A function returning a
 * literal such as {ok, 42} compiles to move {literal, {ok, 42}}, which used to
 * be read as a register operand because the term is a two element tuple.
 */
public class TupleLiteralTest {

    private static final Path SOURCE = Paths.get("src/test/resources/tuple_literal_demo.beam");
    private static final Path LOCAL = Paths.get("tuple_literal_demo.beam");
    private static final Atom MODULE = Atom.of("tuple_literal_demo");

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
    public void testLiteralOkTuple() throws Throwable {
        assertEquals(Tuple.of(Atom.of("ok"), Integer.of(42)),
            apply("ok_pair"));
    }

    @Test
    public void testLiteralTupleOfAtoms() throws Throwable {
        assertEquals(Tuple.of(Atom.of("a"), Atom.of("b")), apply("pair"));
    }

    @Test
    public void testNestedLiteralTuple() throws Throwable {
        assertEquals(Tuple.of(Atom.of("ok"), Tuple.of(Atom.of("a"), Atom.of("b"))),
            apply("nested"));
    }

    @Test
    public void testTupleBuiltAtRuntime() throws Throwable {
        assertEquals(Tuple.of(Atom.of("ok"), Integer.of(7)),
            apply("tagged", Integer.of(7)));
    }

    @Test
    public void testPatternMatchOnLiteralTuple() throws Throwable {
        assertEquals(Integer.of(7),
            apply("match", Tuple.of(Atom.of("ok"), Integer.of(7))));
        assertEquals(Atom.of("none"),
            apply("match", Tuple.of(Atom.of("error"), Integer.of(7))));
    }

    @Test
    public void testGuardComparesAgainstLiteralTuple() throws Throwable {
        assertEquals(Atom.of("yes"),
            apply("guard", Tuple.of(Atom.of("ok"), Integer.of(1))));
        assertEquals(Atom.of("no"),
            apply("guard", Tuple.of(Atom.of("ok"), Integer.of(2))));
    }

    @Test
    public void testRunExercisesEverything() throws Throwable {
        assertEquals(Atom.of("ok"), Erlang.apply(MODULE, Atom.of("run"), List.nil));
    }

    private Term apply(String function, Term... arguments) throws Throwable {
        return Erlang.apply(MODULE, Atom.of(function), List.of(arguments));
    }

}
