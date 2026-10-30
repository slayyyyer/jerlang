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
 * Runs cross_module_demo.beam and cross_module_worker.beam, compiled from
 * src/test/erl by Erlang/OTP 29.
 *
 * Every case puts runtime work in an activation below the caller's: a receive
 * that parks in the worker module, a fun whose lambda lives in the caller, and
 * a terminating tail call that has to release the worker's stack frame.
 */
public class CrossModuleCallTest {

    private static final Path RESOURCES = Paths.get("src/test/resources");
    private static final String[] MODULES = {"cross_module_demo", "cross_module_worker"};
    private static final Atom MODULE = Atom.of("cross_module_demo");

    @Before
    public void start() throws Exception {
        for (String module : MODULES) {
            Files.copy(RESOURCES.resolve(module + ".beam"), Paths.get(module + ".beam"),
                StandardCopyOption.REPLACE_EXISTING);
        }
        VirtualMachine.instance().start();
    }

    @After
    public void stop() throws Exception {
        VirtualMachine.instance().shutdown();
        for (String module : MODULES) {
            Files.deleteIfExists(Paths.get(module + ".beam"));
        }
    }

    @Test
    public void testReceiveInsideAnotherModuleResumesAndReturns() throws Throwable {
        // The message arrives while the process is parked in the worker's
        // receive, so both the worker's activation and this call have to be
        // resumed.
        assertEquals(Tuple.of(Atom.of("waited"), Tuple.of(Atom.of("got"), Integer.of(7))),
            apply("wait_run"));
    }

    @Test
    public void testFunBuiltHereAndAppliedInAnotherModule() throws Throwable {
        assertEquals(Integer.of(15), apply("fun_run"));
    }

    @Test
    public void testCrossModuleTailCallReleasesItsFrame() throws Throwable {
        // combine(count(4), count(5)) = 5 * 1000 + 6. The worker ends in a
        // cross-module tail call with the first argument in a Y register, so
        // its frame has to be deallocated for the caller's stack to line up.
        assertEquals(Tuple.of(Atom.of("tailed"), Integer.of(5006)),
            apply("tail_run", Atom.of("tailed")));
    }

    @Test
    public void testRunCombinesAllCases() throws Throwable {
        assertEquals(Tuple.of(
                Tuple.of(Atom.of("waited"), Tuple.of(Atom.of("got"), Integer.of(7))),
                Integer.of(15),
                Tuple.of(Atom.of("tailed"), Integer.of(5006))),
            apply("run"));
    }

    private Term apply(String function, Term... arguments) throws Throwable {
        return Erlang.apply(MODULE, Atom.of(function), List.of(arguments));
    }

}
