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
 * The milestone 0.7 fairness exit: with twice as many CPU-bound processes as
 * schedulers, a process waiting for a 1 ms timer still wakes up promptly.
 *
 * The fixture runs for 300 ms and returns the 99th percentile wakeup interval.
 * Without reduction preemption the first burner holds a scheduler for the
 * whole run, and the p99 is the full deadline.
 */
public class SchedulerFairnessTest {

    private static final Path BEAM = Paths.get("src/test/resources/scheduler_demo.beam");
    private static final Path LOCAL = Paths.get("scheduler_demo.beam");
    private static final int SCHEDULERS = 4;
    private static final int LIMIT_MILLIS = 50;

    @Before
    public void startVirtualMachine() throws Exception {
        Files.copy(BEAM, LOCAL, StandardCopyOption.REPLACE_EXISTING);
        VirtualMachine.instance().start(SCHEDULERS);
    }

    @After
    public void stopVirtualMachine() throws Exception {
        VirtualMachine.instance().shutdown();
        Files.deleteIfExists(LOCAL);
    }

    @Test
    public void testPingLatencyUnderCpuLoad() throws Throwable {
        int schedulers = SCHEDULERS;
        Term result = Erlang.apply(Atom.of("scheduler_demo"), Atom.of("fairness"),
            List.of(Integer.of(schedulers)));

        assertTrue("fairness failed: " + result, result instanceof Tuple);
        Tuple tuple = (Tuple) result;
        assertEquals("fairness failed: " + result, Atom.of("ok"), tuple.element(1));
        int p99 = tuple.element(2).toInteger().toInt();
        int samples = tuple.element(3).toInteger().toInt();
        System.out.println("scheduler fairness: p99=" + p99 + "ms, samples=" + samples
            + ", schedulers=" + schedulers + " (" + (schedulers * 2) + " burners)");

        // The timer's real resolution is the operating system's, not the
        // requested 1 ms: Windows can be around 15 ms. A dozen samples is
        // enough to see starvation, while a starved process produces one
        // wakeup at the end of the run.
        assertTrue("only " + samples + " wakeups were measured (p99 " + p99 + " ms)",
            samples >= 10);
        assertTrue("p99 wakeup was " + p99 + " ms with " + (schedulers * 2)
            + " CPU-bound processes and " + schedulers + " schedulers",
            p99 < LIMIT_MILLIS);
    }

}
