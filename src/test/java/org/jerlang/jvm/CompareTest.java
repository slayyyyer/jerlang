package org.jerlang.jvm;

import static org.junit.Assert.assertEquals;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;

import org.jerlang.erts.Erlang;
import org.jerlang.type.Atom;
import org.jerlang.type.Binary;
import org.jerlang.type.Boolean;
import org.jerlang.type.Float;
import org.jerlang.type.Integer;
import org.jerlang.type.List;
import org.jerlang.type.Term;
import org.jerlang.type.Tuple;
import org.jerlang.vm.VirtualMachine;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/**
 * Runs compare_demo.beam, compiled by OTP 29. A comparison in a function body
 * compiles to a bif such as erlang:'&lt;'/2 while a guard compiles to is_lt or
 * is_ge, so the module covers both instruction paths with literals, X
 * registers and Y registers on either side.
 */
public class CompareTest {

    private static final Path SOURCE = Paths.get("src/test/resources/compare_demo.beam");
    private static final Path LOCAL = Paths.get("compare_demo.beam");
    private static final Atom MODULE = Atom.of("compare_demo");
    private static final Atom TRUE = Boolean.am_true;
    private static final Atom FALSE = Boolean.am_false;

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
    public void testLessThanWithBothOperandOrders() throws Throwable {
        assertEquals(TRUE, apply("lt", Integer.of(1), Integer.of(2)));
        assertEquals(FALSE, apply("lt", Integer.of(2), Integer.of(1)));
        assertEquals(FALSE, apply("lt", Integer.of(2), Integer.of(2)));
    }

    @Test
    public void testLessThanAcrossTermTypes() throws Throwable {
        assertEquals(TRUE, apply("lt", Integer.of(1), Atom.of("a")));
        assertEquals(TRUE, apply("lt", Atom.of("a"), Tuple.of(Integer.of(1))));
        assertEquals(TRUE, apply("lt", Tuple.of(Integer.of(1)),
            List.of(Integer.of(1))));
        assertEquals(TRUE, apply("lt", List.nil, List.of(Integer.of(1))));
        assertEquals(TRUE, apply("lt", Float.of(1.0), Float.of(1.5)));
        assertEquals(TRUE, apply("lt", Binary.of(1), Binary.of(1, 0)));
        assertEquals(TRUE, apply("lt",
            Tuple.of(Atom.of("a"), Integer.of(2)),
            Tuple.of(Atom.of("b"), Integer.of(1))));
        // Numbers compare by value across integers and floats.
        assertEquals(FALSE, apply("lt", Integer.of(1), Float.of(1.0)));
    }

    @Test
    public void testLessOrEqual() throws Throwable {
        assertEquals(TRUE, apply("le", Integer.of(2), Integer.of(2)));
        assertEquals(TRUE, apply("le", Integer.of(1), Integer.of(2)));
        assertEquals(FALSE, apply("le", Integer.of(3), Integer.of(2)));
        assertEquals(TRUE, apply("le", Integer.of(1), Float.of(1.0)));
    }

    @Test
    public void testGreaterThanAndGreaterOrEqual() throws Throwable {
        assertEquals(TRUE, apply("gt", Integer.of(2), Integer.of(1)));
        assertEquals(FALSE, apply("gt", Integer.of(1), Integer.of(2)));
        assertEquals(TRUE, apply("ge", Integer.of(2), Integer.of(2)));
        assertEquals(FALSE, apply("ge", Integer.of(1), Integer.of(2)));
        assertEquals(TRUE, apply("ge", Atom.of("b"), Atom.of("a")));
    }

    @Test
    public void testNumericEquality() throws Throwable {
        assertEquals(TRUE, apply("eq", Integer.of(1), Float.of(1.0)));
        assertEquals(FALSE, apply("eq", Integer.of(1), Integer.of(2)));
        assertEquals(TRUE, apply("eq", Atom.of("a"), Atom.of("a")));
        assertEquals(TRUE, apply("ne", Integer.of(1), Integer.of(2)));
        assertEquals(FALSE, apply("ne", Integer.of(1), Float.of(1.0)));
    }

    @Test
    public void testExactEquality() throws Throwable {
        assertEquals(TRUE, apply("eq_exact", Integer.of(1), Integer.of(1)));
        assertEquals(FALSE, apply("eq_exact", Integer.of(1), Float.of(1.0)));
        assertEquals(TRUE, apply("ne_exact", Integer.of(1), Float.of(1.0)));
        assertEquals(FALSE, apply("ne_exact", Integer.of(1), Integer.of(1)));
    }

    @Test
    public void testGuardsInBothOrders() throws Throwable {
        assertEquals(Atom.of("yes"), apply("guard_lt", Integer.of(1), Integer.of(2)));
        assertEquals(Atom.of("no"), apply("guard_lt", Integer.of(2), Integer.of(1)));
        assertEquals(Atom.of("yes"), apply("guard_ge", Integer.of(2), Integer.of(2)));
        assertEquals(Atom.of("no"), apply("guard_ge", Integer.of(1), Integer.of(2)));
    }

    @Test
    public void testEqualityGuards() throws Throwable {
        assertEquals(Atom.of("yes"), apply("guard_eq", Integer.of(1), Float.of(1.0)));
        assertEquals(Atom.of("no"), apply("guard_eq", Integer.of(1), Integer.of(2)));
        assertEquals(Atom.of("yes"), apply("guard_ne", Integer.of(1), Integer.of(2)));
        assertEquals(Atom.of("no"), apply("guard_ne", Integer.of(1), Float.of(1.0)));
        assertEquals(Atom.of("yes"), apply("guard_eq_exact", Integer.of(1), Integer.of(1)));
        assertEquals(Atom.of("no"), apply("guard_eq_exact", Integer.of(1), Float.of(1.0)));
        assertEquals(Atom.of("yes"), apply("guard_ne_exact", Integer.of(1), Float.of(1.0)));
        assertEquals(Atom.of("no"), apply("guard_ne_exact", Integer.of(1), Integer.of(1)));
    }

    @Test
    public void testGuardsWithLiteralsOnEitherSide() throws Throwable {
        // is_lt and is_ge with a register and a literal in both orders.
        assertEquals(Atom.of("yes"), apply("small", Integer.of(5)));
        assertEquals(Atom.of("no"), apply("small", Integer.of(20)));
        assertEquals(Atom.of("yes"), apply("at_most", Integer.of(12)));
        assertEquals(Atom.of("no"), apply("at_most", Integer.of(13)));
        assertEquals(Atom.of("yes"), apply("big", Integer.of(20)));
        assertEquals(Atom.of("no"), apply("big", Integer.of(5)));
        assertEquals(Atom.of("yes"), apply("at_least", Integer.of(12)));
        assertEquals(Atom.of("no"), apply("at_least", Integer.of(11)));
    }

    @Test
    public void testYRegisters() throws Throwable {
        assertEquals(TRUE, apply("y_lt", Integer.of(1), Integer.of(2)));
        assertEquals(FALSE, apply("y_lt", Integer.of(2), Integer.of(1)));
        assertEquals(Atom.of("yes"), apply("y_guard", Integer.of(1), Integer.of(2)));
        assertEquals(Atom.of("no"), apply("y_guard", Integer.of(2), Integer.of(1)));
    }

    @Test
    public void testRunExercisesEverything() throws Throwable {
        assertEquals(Atom.of("ok"), Erlang.apply(MODULE, Atom.of("run"), List.nil));
    }

    private Term apply(String function, Term... arguments) throws Throwable {
        return Erlang.apply(MODULE, Atom.of(function), List.of(arguments));
    }

}
