package org.jerlang.erts.dist;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

import java.util.Arrays;

import org.jerlang.type.Atom;
import org.jerlang.type.Binary;
import org.jerlang.type.Integer;
import org.jerlang.type.List;
import org.jerlang.type.PID;
import org.jerlang.type.Term;
import org.jerlang.type.Tuple;
import org.junit.Assume;
import org.junit.Before;
import org.junit.Test;

/**
 * The distribution row the benchmark suite did not have: encode and decode a
 * real 112-tagged packet with a control term and a binary message.
 *
 * This measures the codec, not the socket. A two-peer wall-clock benchmark
 * would also include the kernel's TCP stack and two scheduler hand-offs, so it
 * would measure the machine's loopback more than this runtime. The codec is the
 * part that changes with this codebase, and it is the part this row isolates.
 *
 * Run it with:
 *
 * <pre>
 * mvn -Dtest=DistributionBenchmarkTest -Djerlang.bench=true test
 * </pre>
 */
public class DistributionBenchmarkTest {

    private static final Atom PEER = Atom.of("peer@127.0.0.1");
    private static final int REPEATS = 5;

    /** Keeps a decoded result reachable so the JIT can not delete the work. */
    private static Term sink;

    @Before
    public void requireOptIn() {
        Assume.assumeTrue("set -Djerlang.bench=true to run the benchmarks",
            Boolean.getBoolean("jerlang.bench"));
    }

    @Test
    public void framingThroughput() throws Exception {
        PID from = new PID(PEER, 55, 0, 0);
        Term control = Tuple.of(Integer.of(6), from, List.nil, Atom.of("sink"));
        Term message = Binary.of(new byte[32]);
        byte[] payload = DistConnection.encodeFrame(control, message);
        assertEquals(control, DistConnection.decodeFrame(payload)[0]);

        int warmup = 5_000;
        int ops = 50_000;
        for (int index = 0; index < warmup; index++) {
            sink = DistConnection.decodeFrame(DistConnection.encodeFrame(control, message))[1];
        }
        assertNotNull(sink);

        double[] samples = new double[REPEATS];
        for (int repeat = 0; repeat < REPEATS; repeat++) {
            long start = System.nanoTime();
            for (int index = 0; index < ops; index++) {
                sink = DistConnection.decodeFrame(
                    DistConnection.encodeFrame(control, message))[1];
            }
            samples[repeat] = (double) (System.nanoTime() - start) / ops;
        }
        assertEquals(message, sink);
        Arrays.sort(samples);
        double median = samples[samples.length / 2];

        System.out.println();
        System.out.println("Distribution frame codec benchmark");
        System.out.printf(java.util.Locale.ROOT,
            "encode+decode %d bytes: %.1f ns/op, %,.0f frames/sec%n",
            payload.length, median, 1e9 / median);
    }

}
