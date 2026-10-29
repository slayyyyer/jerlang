package org.jerlang.jvm;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.Arrays;

import org.jerlang.erts.Erlang;
import org.jerlang.type.Atom;
import org.jerlang.type.Integer;
import org.jerlang.type.List;
import org.jerlang.vm.VirtualMachine;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/**
 * A call from a Java thread into Erlang must not wait out a scheduler poll.
 *
 * The scheduler used to sleep for 100 ms whenever its run queues were empty,
 * and the caller polled the spawned process every 10 ms on top of that, so a
 * trivial call cost about 108 ms. Nothing in the suite noticed, because the
 * tests only checked results.
 *
 * The bound is deliberately loose: the measured median is around 0.1 ms, so
 * there are two orders of magnitude of headroom for a loaded machine, while a
 * reintroduced sleep still fails. It is a median rather than a maximum for the
 * same reason.
 */
public class CrossBoundaryCallTest {

    private static final Path BEAM = Paths.get("src/test/resources/compare_demo.beam");
    private static final Path LOCAL = Paths.get("compare_demo.beam");
    private static final double LIMIT_MILLIS = 25.0;

    @Before
    public void start() throws Exception {
        Files.copy(BEAM, LOCAL, StandardCopyOption.REPLACE_EXISTING);
        VirtualMachine.instance().start();
    }

    @After
    public void stop() throws Exception {
        VirtualMachine.instance().shutdown();
        Files.deleteIfExists(LOCAL);
    }

    @Test
    public void testCallDoesNotWaitForASchedulerPoll() throws Throwable {
        Atom module = Atom.of("compare_demo");
        Atom function = Atom.of("lt");
        List params = List.of(Integer.of(1), Integer.of(2));

        // Warm up: the first calls also load the module and start the JIT.
        for (int index = 0; index < 3; index++) {
            assertEquals(Atom.of("true"), Erlang.apply(module, function, params));
        }

        double[] samples = new double[5];
        for (int index = 0; index < samples.length; index++) {
            long start = System.nanoTime();
            assertEquals(Atom.of("true"), Erlang.apply(module, function, params));
            samples[index] = (System.nanoTime() - start) / 1_000_000.0;
        }
        Arrays.sort(samples);
        double median = samples[samples.length / 2];

        assertTrue("a cross-boundary call took " + median + " ms; an idle scheduler "
            + "is probably sleeping again instead of waiting for work", median < LIMIT_MILLIS);
    }

}
