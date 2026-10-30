package org.jerlang.jvm;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.jerlang.AbstractVirtualMachineTest;
import org.jerlang.CodePaths;
import org.jerlang.ModuleRegistry;
import org.jerlang.erts.Erlang;
import org.jerlang.type.Atom;
import org.jerlang.type.Integer;
import org.jerlang.type.List;
import org.jerlang.type.Term;
import org.jerlang.type.Tuple;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/**
 * Runs stdlib_boot_demo.beam, compiled from
 * src/test/erl/stdlib_boot_demo.erl by Erlang/OTP 29. It covers error/3,
 * whose youngest stack frame carries the caller's argument list, and
 * persistent_term, which is where logger and application keep their
 * configuration.
 */
public class StdlibBootTest extends AbstractVirtualMachineTest {

    private static final Atom OK = Atom.of("ok");
    private static final Atom BADARG = Atom.of("badarg");

    @Before
    public void addDemoPath() {
        CodePaths.reset();
        ModuleRegistry.reset();
        CodePaths.addPatha(CodePaths.normalizePath("src/test/resources"));
    }

    @After
    public void resetCodeServer() {
        CodePaths.reset();
        ModuleRegistry.reset();
    }

    @Test
    public void error3PutsTheArgumentListInTheYoungestFrame() throws Throwable {
        Tuple expected = Tuple.of(
            Tuple.of(Atom.of("tagged"), Integer.of(1)),
            List.of(Atom.of("alpha"), Atom.of("beta")));

        assertEquals(expected, call("stdlib_boot_demo", "error3"));
    }

    @Test
    public void error3AcceptsErrorInfoAndAnEmptyArgumentList() throws Throwable {
        assertEquals(Atom.of("error_info_accepted"),
            call("stdlib_boot_demo", "error3_info"));
    }

    @Test
    public void error3RejectsAnyOtherOption() throws Throwable {
        assertEquals(BADARG, call("stdlib_boot_demo", "error3_bad_options"));
    }

    @Test
    public void persistentTermStoresReplacesAndErases() throws Throwable {
        assertEquals(OK, call("stdlib_boot_demo", "persistent_term_roundtrip"));
    }

    @Test
    public void persistentTermGetOnAMissingKeyIsABadarg() throws Throwable {
        assertEquals(BADARG, call("stdlib_boot_demo", "persistent_term_missing"));
    }

    @Test
    public void persistentTermInfoReportsACountAndMemory() throws Throwable {
        Term info = call("stdlib_boot_demo", "persistent_term_info").toMap();

        assertTrue(((Integer) info.toMap().get(Atom.of("count"))).toInt() >= 1);
        assertEquals(Integer.of(0), info.toMap().get(Atom.of("memory")));
    }

    @Test
    public void processInfoAnswersTheItemsProcLibNeeds() throws Throwable {
        Tuple expected = Tuple.of(List.nil, Atom.of("running"), Atom.of("true"));

        assertEquals(expected, call("stdlib_boot_demo", "process_info_report"));
    }

    @Test
    public void funInfoMfaNamesTheModuleFunctionAndArity() throws Throwable {
        Tuple mfa = call("stdlib_boot_demo", "fun_info_report").toTuple();

        assertEquals(Atom.of("stdlib_boot_demo"), mfa.element(1));
        assertEquals(Integer.of(2), mfa.element(3));
    }

    @Test
    public void persistentTermGetReturnsEveryStoredTerm() throws Throwable {
        Tuple key = Tuple.of(Atom.of("stdlib_boot_test"), Integer.of(1));
        assertEquals(Atom.of("true"), call("persistent_term", "put", key, Integer.of(9)));

        assertTrue(contains(call("persistent_term", "get"), Tuple.of(key, Integer.of(9))));

        assertEquals(Atom.of("true"), call("persistent_term", "erase", key));
    }

    private static boolean contains(Term list, Term term) {
        List current = list.toList();
        while (current.length() > 0) {
            if (current.head().equals(term)) {
                return true;
            }
            current = current.tail();
        }
        return false;
    }

    private static Term call(String module, String function, Term... args) throws Throwable {
        return Erlang.apply(Atom.of(module), Atom.of(function), List.of(args));
    }

}
