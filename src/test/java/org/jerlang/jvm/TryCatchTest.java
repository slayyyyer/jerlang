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
import org.jerlang.type.Str;
import org.jerlang.type.Term;
import org.jerlang.type.Tuple;
import org.jerlang.vm.VirtualMachine;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/**
 * Runs try_demo.beam, which was compiled from src/test/erl/try_demo.erl by
 * Erlang/OTP 29 and uses try, try_case, try_end, build_stacktrace and
 * raw_raise.
 */
public class TryCatchTest {

    private static final Path SOURCE = Paths.get("src/test/resources/try_demo.beam");
    private static final Path LOCAL = Paths.get("try_demo.beam");

    private static final Atom MODULE = Atom.of("try_demo");
    private static final Atom ERROR = Atom.of("error");
    private static final Atom FILE = Atom.of("file");
    private static final Atom LINE = Atom.of("line");
    private static final Str SOURCE_FILE = Str.of("try_demo.erl");

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

    /**
     * A stacktrace frame: {Module, Function, Arity, [{file, Source}, {line, N}]}.
     */
    private static Term frame(String function, int arity, int line) {
        return Tuple.of(MODULE, Atom.of(function), Integer.of(arity),
            List.of(
                Tuple.of(FILE, SOURCE_FILE),
                Tuple.of(LINE, Integer.of(line))));
    }

    @Test
    public void testCatchBindsClassReasonAndStacktrace() throws Throwable {
        // The frame points at the erlang:error/1 call on line 14 of caught/1.
        Term result = Erlang.apply(MODULE, Atom.of("caught"), List.of(Atom.of("boom")));
        assertEquals(Tuple.of(ERROR, Atom.of("boom"),
            List.of(frame("caught", 1, 14))), result);
    }

    @Test
    public void testCatchClausesAreMatchedInOrder() throws Throwable {
        assertEquals(Atom.of("badarg_clause"),
            Erlang.apply(MODULE, Atom.of("caught_by_class"), List.of(Atom.of("badarg"))));
        assertEquals(Tuple.of(ERROR, Atom.of("other")),
            Erlang.apply(MODULE, Atom.of("caught_by_class"), List.of(Atom.of("other"))));
    }

    @Test
    public void testRawRaiseReraisesClassReasonAndStacktrace() throws Throwable {
        // reraise/1 catches an error and uses erlang:raise/3, which compiles
        // to raw_raise; reraise_caught/1 catches it again with the original
        // stacktrace.
        Term result = Erlang.apply(MODULE, Atom.of("reraise_caught"), List.of(Atom.of("again")));
        assertEquals(Tuple.of(Atom.of("reraised"), ERROR, Atom.of("again"),
            List.of(
                frame("reraise", 1, 33),
                frame("reraise_caught", 1, 43))), result);
    }

    @Test
    public void testUnmatchedCatchClauseReraisesUnchanged() throws Throwable {
        // rethrow/1 catches only throws, so the error is re-raised by the
        // internal raise bif and caught again by rethrow_caught/1.
        Term result = Erlang.apply(MODULE, Atom.of("rethrow_caught"), List.of(Atom.of("unmatched")));
        assertEquals(Tuple.of(Atom.of("reraised_by_bif"), ERROR, Atom.of("unmatched")), result);
    }

    @Test
    public void testRunCombinesAllCasesWithNestedFrames() throws Throwable {
        Term result = Erlang.apply(MODULE, Atom.of("run"), List.nil);
        assertEquals(Tuple.of(
            Tuple.of(ERROR, Atom.of("boom"), List.of(
                frame("caught", 1, 14),
                frame("run", 0, 67))),
            Tuple.of(ERROR, Atom.of("badarith")),
            Tuple.of(Atom.of("reraised"), ERROR, Atom.of("again"), List.of(
                frame("reraise", 1, 33),
                frame("reraise_caught", 1, 43),
                frame("run", 0, 69))),
            Tuple.of(Atom.of("reraised_by_bif"), ERROR, Atom.of("unmatched"))), result);
    }

}
