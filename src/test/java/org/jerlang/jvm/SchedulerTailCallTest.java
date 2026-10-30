package org.jerlang.jvm;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

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
 * Cross-module tail calls continue in the interpreter loop instead of nesting
 * Java frames. A chain that replaces an activation has to return to the local
 * caller it inherited, and those continuations have to survive a wait.
 *
 * The fairness test runs a loop that alternates two modules. Without the
 * trampoline the JVM stack grows every iteration, so the loop cannot run for
 * 300 ms and a CPU-bound process cannot be preempted between modules.
 */
public class SchedulerTailCallTest {

    private static final Path RESOURCES = Paths.get("src/test/resources");
    private static final String[] MODULES = {
        "scheduler_tail_demo", "scheduler_tail_a", "scheduler_tail_b"
    };
    private static final int SCHEDULERS = 4;
    private static final int LIMIT_MILLIS = 50;

    @Before
    public void startVirtualMachine() throws Exception {
        for (String module : MODULES) {
            Files.copy(RESOURCES.resolve(module + ".beam"),
                Paths.get(module + ".beam"), StandardCopyOption.REPLACE_EXISTING);
        }
        VirtualMachine.instance().start(SCHEDULERS);
    }

    @After
    public void stopVirtualMachine() throws Exception {
        VirtualMachine.instance().shutdown();
        for (String module : MODULES) {
            Files.deleteIfExists(Paths.get(module + ".beam"));
        }
    }

    @Test
    public void testChainReturnsToLocalContinuation() throws Throwable {
        Term result = Erlang.apply(Atom.of("scheduler_tail_demo"), Atom.of("result"), List.nil);
        assertEquals(Integer.of(11), result);
    }

    @Test
    public void testChainSurvivesAWait() throws Throwable {
        Term result = Erlang.apply(Atom.of("scheduler_tail_demo"), Atom.of("delayed"), List.nil);
        assertEquals(Tuple.of(Atom.of("hello"), Atom.of("done")), result);
    }

    @Test
    public void testCrossModuleTailLoopIsPreemptible() throws Throwable {
        Term result = Erlang.apply(Atom.of("scheduler_tail_demo"), Atom.of("fairness"),
            List.of(Integer.of(SCHEDULERS)));

        assertTrue("fairness failed: " + result, result instanceof Tuple);
        Tuple tuple = (Tuple) result;
        assertEquals("fairness failed: " + result, Atom.of("ok"), tuple.element(1));
        int p99 = tuple.element(2).toInteger().toInt();
        int samples = tuple.element(3).toInteger().toInt();
        System.out.println("cross-module tail call fairness: p99=" + p99 + "ms, samples="
            + samples + ", schedulers=" + SCHEDULERS);

        assertTrue("only " + samples + " wakeups were measured (p99 " + p99 + " ms)",
            samples >= 10);
        assertTrue("p99 wakeup was " + p99 + " ms with " + (SCHEDULERS * 2)
            + " burners alternating modules", p99 < LIMIT_MILLIS);
    }

}
