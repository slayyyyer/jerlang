package org.jerlang.jvm;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;

import org.jerlang.ProcessRegistry;
import org.jerlang.erts.Erlang;
import org.jerlang.type.Atom;
import org.jerlang.type.Boolean;
import org.jerlang.type.Integer;
import org.jerlang.type.List;
import org.jerlang.type.Term;
import org.jerlang.type.Tuple;
import org.jerlang.vm.VirtualMachine;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/**
 * The remaining milestone 0.7 exit checks: 10k spawn/exit cycles leave no
 * registry entries, a timer at 10 ms fires within a stated bound, and a
 * link/monitor stress run loses no signal. The class also covers the timer
 * wheel's three entry points, the new process_info fields, priority
 * scheduling and call/message tracing.
 */
public class Milestone7Test {

    private static final Path BEAM = Paths.get("src/test/resources/milestone7_demo.beam");
    private static final Path LOCAL = Paths.get("milestone7_demo.beam");
    private static final int SCHEDULERS = 4;
    private static final int TIMER_MILLIS = 10;
    private static final int TIMER_BOUND_MILLIS = 250;

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
    public void testSpawnExitCyclesLeaveRegistryClean() throws Throwable {
        int baseline = ProcessRegistry.all().size();
        Term result = apply("spawn_cycles", List.of(Integer.of(10000)));
        assertEquals(Atom.of("ok"), result);
        assertEquals("registry retained entries after 10k spawn/exit cycles",
            baseline, ProcessRegistry.all().size());
    }

    @Test
    public void testTenMillisecondTimersFireWithinBound() throws Throwable {
        Term result = apply("timer_bound", List.of(Integer.of(TIMER_MILLIS)));
        assertTrue("timer fixture failed: " + result, result instanceof Tuple);
        Tuple elapsed = (Tuple) result;
        assertEquals(4, elapsed.arity());
        for (int index = 1; index <= 4; index++) {
            int millis = elapsed.element(index).toInteger().toInt();
            System.out.println("timer form " + index + ": " + millis + "ms");
            assertTrue("timer form " + index + " fired after " + millis + "ms",
                millis >= TIMER_MILLIS - 2);
            assertTrue("timer form " + index + " fired after " + millis + "ms, bound "
                + TIMER_BOUND_MILLIS, millis <= TIMER_BOUND_MILLIS);
        }
    }

    @Test
    public void testLinkMonitorStressLosesNoSignal() throws Throwable {
        Term result = apply("signals", List.of(Integer.of(500)));
        assertEquals(Tuple.of(Atom.of("ok"), Integer.of(500), Integer.of(500)), result);
    }

    @Test
    public void testProcessInfoFields() throws Throwable {
        Tuple result = (Tuple) apply("info", List.nil);
        assertEquals(Boolean.am_true, result.element(1));
        assertEquals(Atom.of("off_heap"), result.element(2));
        assertEquals(Atom.of("running"), result.element(3));
        Tuple current = (Tuple) result.element(4);
        assertEquals(3, current.arity());
        assertEquals(Atom.of("milestone7_demo"), current.element(1));
        assertEquals(Integer.ZERO, result.element(5));
    }

    @Test
    public void testPriorityReordersTheRunQueue() throws Throwable {
        // One scheduler makes the order observable: the high-priority child
        // is queued last but must run first.
        VirtualMachine.instance().shutdown();
        VirtualMachine.instance().start(1);
        Term result = apply("priority", List.of(Integer.of(5)));
        assertTrue("priority fixture failed: " + result, result instanceof List);
        List order = ((List) result).toList();
        assertEquals(6, order.length());
        assertEquals(Atom.of("high"), order.head());
    }

    @Test
    public void testCallTracing() throws Throwable {
        Term result = apply("trace_calls", List.nil);
        assertTrue("call trace missing: " + result, contains(result, Atom.of("call")));
    }

    @Test
    public void testTailCallTracing() throws Throwable {
        Term result = apply("trace_tail_calls", List.nil);
        assertTrue("tail call trace missing: " + result,
            contains(result, Atom.of("call")));
    }

    @Test
    public void testMessageTracing() throws Throwable {
        Term result = apply("trace_messages", List.nil);
        assertTrue("send trace missing: " + result, contains(result, Atom.of("send")));
        assertTrue("receive trace missing: " + result, contains(result, Atom.of("receive")));
    }

    private static Term apply(String function, List args) throws Throwable {
        return Erlang.apply(Atom.of("milestone7_demo"), Atom.of(function), args);
    }

    private static boolean contains(Term list, Term expected) {
        List rest = list.toList();
        while (rest.length() > 0) {
            if (expected.equals(rest.head())) {
                return true;
            }
            rest = rest.tail();
        }
        return false;
    }

}
