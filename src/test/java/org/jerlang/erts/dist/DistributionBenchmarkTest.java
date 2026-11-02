package org.jerlang.erts.dist;

import static org.junit.Assert.assertEquals;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicReference;

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
 * The distribution benchmark: the 112-tagged codec on its own, and a
 * loopback transport round trip through a real socket.
 *
 * The codec row isolates encoding and decoding. The loopback row adds the
 * kernel's TCP stack and a peer thread, but still excludes a second JVM and
 * the scheduler hand-offs of a full node-to-node run. Both are opt-in because
 * they measure the machine.
 *
 * Run with:
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

    @Test
    public void loopbackRoundTripThroughput() throws Exception {
        PID from = new PID(PEER, 55, 0, 0);
        Term control = Tuple.of(Integer.of(6), from, List.nil, Atom.of("sink"));
        Term message = Binary.of(new byte[32]);
        byte[] payload = DistConnection.encodeFrame(control, message);

        AtomicReference<Throwable> failure = new AtomicReference<>();
        ServerSocket server = new ServerSocket(0, 1, InetAddress.getLoopbackAddress());
        Thread peer = new Thread(() -> {
            try (Socket socket = server.accept()) {
                DataInputStream in = new DataInputStream(socket.getInputStream());
                DataOutputStream out = new DataOutputStream(socket.getOutputStream());
                while (true) {
                    int length;
                    try {
                        length = in.readInt();
                    } catch (EOFException end) {
                        return;
                    }
                    if (length == 0) {
                        out.writeInt(0);
                        out.flush();
                        continue;
                    }
                    byte[] request = new byte[length];
                    in.readFully(request);
                    out.writeInt(length);
                    out.write(request);
                    out.flush();
                }
            } catch (Throwable e) {
                if (!server.isClosed()) {
                    failure.set(e);
                }
            }
        }, "dist-bench-loopback-peer");
        peer.setDaemon(true);
        peer.start();

        try (Socket client = new Socket(InetAddress.getLoopbackAddress(),
            server.getLocalPort())) {
            client.setTcpNoDelay(true);
            DataInputStream in = new DataInputStream(client.getInputStream());
            DataOutputStream out = new DataOutputStream(client.getOutputStream());

            int warmup = 200;
            int ops = 2_000;
            for (int index = 0; index < warmup; index++) {
                sink = roundTrip(in, out, payload);
            }
            assertEquals(message, sink);

            double[] samples = new double[REPEATS];
            for (int repeat = 0; repeat < REPEATS; repeat++) {
                long start = System.nanoTime();
                for (int index = 0; index < ops; index++) {
                    sink = roundTrip(in, out, payload);
                }
                samples[repeat] = (double) (System.nanoTime() - start) / ops;
            }
            assertEquals(message, sink);
            Arrays.sort(samples);
            double median = samples[samples.length / 2];

            System.out.println("Distribution loopback transport benchmark");
            System.out.printf(java.util.Locale.ROOT,
                "round trip %d bytes: %.1f ns/op, %,.0f round trips/sec%n",
                payload.length, median, 1e9 / median);
        } finally {
            server.close();
            peer.join(3_000);
        }
        if (failure.get() != null) {
            throw new AssertionError("loopback peer failed", failure.get());
        }
    }

    private static Term roundTrip(DataInputStream in, DataOutputStream out, byte[] payload)
        throws IOException {
        out.writeInt(payload.length);
        out.write(payload);
        out.flush();
        int length = in.readInt();
        byte[] response = new byte[length];
        in.readFully(response);
        return DistConnection.decodeFrame(response)[1];
    }

}
