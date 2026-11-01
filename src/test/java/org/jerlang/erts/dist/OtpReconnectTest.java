package org.jerlang.erts.dist;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

import org.jerlang.Process;
import org.jerlang.ProcessRegistry;
import org.jerlang.erts.Erlang;
import org.jerlang.type.Atom;
import org.jerlang.type.Integer;
import org.jerlang.type.PID;
import org.jerlang.type.Term;
import org.jerlang.type.Tuple;
import org.jerlang.vm.Metrics;
import org.jerlang.vm.VirtualMachine;
import org.junit.After;
import org.junit.Assume;
import org.junit.Before;
import org.junit.Test;

/**
 * The peer kill half of the milestone 0.9 exit test, unattended.
 *
 * The test starts a real Erlang/OTP node itself, so CI runs it without a
 * hand-started peer: it connects, kills the peer, watches this node notice the
 * loss, starts a peer under the same name again and then waits for this node to
 * reconnect on its own. Nothing in the test calls {@code connect_node/1} after
 * the kill; a connection that appears is the reconnect policy from
 * docs/operations.md doing its work. Messages have to flow on that new
 * connection, and the node has to stay observable after it.
 *
 * The peer runs on 127.0.0.1 rather than localhost for the same reason
 * {@code OtpInteropTest} does: OTP rejects node names whose host part is
 * neither a fully qualified domain name nor an IP address.
 *
 * The test needs an Erlang installation: {@code erl} on the PATH, or
 * {@code -Djerlang.otp.dir} pointing at an OTP root with a bin/erl. Without one
 * it is skipped, like the other OTP-backed tests.
 *
 * mvn -Dtest=OtpReconnectTest -Djerlang.otp.dir=/path/to/otp test
 */
public class OtpReconnectTest {

    private static final String COOKIE = "dist-test-cookie";

    /**
     * Names are unique per run so a node started by hand for another test, or
     * a leftover one, can not collide with this one in EPMD. A node name has to
     * be a valid atom, so the run marker is base 36 rather than nanoseconds.
     */
    private static final String RUN = java.lang.Long.toString(System.nanoTime(), 36);

    private static final Atom LOCAL = Atom.of("jerlang_reconnect" + RUN + "@127.0.0.1");
    private static final Atom PEER = Atom.of("peer_reconnect" + RUN + "@127.0.0.1");

    private VirtualMachine vm;
    private Atom previousNode;
    private int previousCreation;
    private boolean started;
    private java.lang.Process peer;
    private Path peerLog;

    @Before
    public void setUp() throws Exception {
        Assume.assumeTrue("needs an Erlang installation: erl on PATH or -Djerlang.otp.dir",
            erlCommand() != null);
        vm = VirtualMachine.instance();
        previousNode = vm.node();
        previousCreation = vm.nodeCreation();
        vm.setNode(LOCAL, 1);
        DistCookie.setCookie(COOKIE);
        peerLog = Files.createTempFile("jerlang-otp-peer", ".log");
        vm.start();
        started = true;
    }

    @After
    public void tearDown() throws Exception {
        if (!started) {
            return;
        }
        stopPeer();
        Distribution.disconnectAll();
        vm.shutdown();
        DistCookie.reset();
        vm.setNode(previousNode, previousCreation);
        try {
            Files.deleteIfExists(peerLog);
        } catch (IOException e) {
            // Windows keeps the file while the peer's handle lingers; the
            // temp directory cleans up on its own.
        }
    }

    @Test
    public void reconnectsToAPeerThatWasKilledAndRestarted() throws Throwable {
        peer = startPeerWhenTheNameIsFree();
        await("the peer never answered a ping: " + PEER, 60000, this::ping);
        assertTrue("the peer is not connected", Distribution.isConnected(PEER));
        assertEquals(org.jerlang.type.List.of(PEER), Erlang.nodes());

        Process sink = new Process(new PID(211));
        ProcessRegistry.register(sink);
        ProcessRegistry.register(Atom.of("reconnect_sink"), sink.id());
        assertEcho(sink, 1);

        stopPeer();
        await("this node never noticed the peer going away", 30000,
            () -> !Distribution.isConnected(PEER));
        assertEquals("a lost peer must not stay in erlang:nodes/0",
            org.jerlang.type.List.nil, Erlang.nodes());

        // The peer comes back under the same name. Nothing connects it here:
        // the retry loop from DistReconnect has to find it again, and the
        // nodeup that follows has to show up in erlang:nodes/0.
        peer = startPeerWhenTheNameIsFree();
        await("this node never reconnected to the restarted peer", 90000,
            () -> Distribution.isConnected(PEER));
        assertEquals(org.jerlang.type.List.of(PEER), Erlang.nodes());
        assertEcho(sink, 3);

        // A node that reconnected is still a node that can be observed.
        Metrics metrics = Metrics.snapshot();
        assertTrue(metrics.processes() >= 1);
        assertTrue(metrics.toText().contains("jerlang_processes"));
    }

    /**
     * A peer that echoes {From, Msg} back as {echo, Msg}, and stays up until it
     * is killed. The eval expression has no quotes: a quote in the argument is
     * escaped by the Windows command line, which the emulator does not undo.
     */
    private java.lang.Process startPeer() throws IOException {
        List<String> command = new ArrayList<>();
        command.add(erlCommand());
        command.add("-name");
        command.add(PEER.toString());
        command.add("-setcookie");
        command.add(COOKIE);
        command.add("-noshell");
        command.add("-eval");
        command.add("register(echo, spawn(fun Loop() -> receive"
            + " {From, Msg} -> From ! {echo, Msg}, Loop() end end)),"
            + " receive after infinity -> ok end.");
        ProcessBuilder builder = new ProcessBuilder(command);
        builder.redirectErrorStream(true);
        builder.redirectOutput(peerLog.toFile());
        String epmdPort = System.getProperty("jerlang.epmd.port");
        if (epmdPort != null) {
            builder.environment().put("ERL_EPMD_PORT", epmdPort);
        }
        return builder.start();
    }

    /**
     * A killed node lingers in EPMD for a moment after its socket closes, and a
     * peer that starts too early exits with a name clash. A peer that is still
     * up after two seconds started fine; one that exited is started again.
     */
    private java.lang.Process startPeerWhenTheNameIsFree() throws IOException, InterruptedException {
        for (int attempt = 1; attempt <= 5; attempt++) {
            java.lang.Process startedPeer = startPeer();
            if (!startedPeer.waitFor(2, TimeUnit.SECONDS)) {
                return startedPeer;
            }
            Thread.sleep(200);
        }
        throw new IllegalStateException("the node name " + PEER
            + " stayed registered in EPMD, so the peer could not start: " + peerOutput());
    }

    private void stopPeer() throws InterruptedException {
        java.lang.Process current = peer;
        peer = null;
        if (current == null) {
            return;
        }
        current.destroyForcibly();
        assertTrue("the peer did not exit: " + peerOutput(),
            current.waitFor(15, TimeUnit.SECONDS));
    }

    private boolean ping() {
        try {
            return Atom.of("pong").equals(Erlang.apply(Atom.of("net_adm"),
                Atom.of("ping"), org.jerlang.type.List.of(PEER)));
        } catch (RuntimeException e) {
            // A peer that is still booting is not an error, just not up yet.
            return false;
        }
    }

    private void assertEcho(Process sink, int number) throws InterruptedException {
        Term message = Tuple.of(Atom.of("hello"), Integer.of(number));
        Distribution.send(Tuple.of(Atom.of("echo"), PEER), sink.pid(),
            Tuple.of(sink.pid(), message));
        Term reply = awaitMessage(sink, 10000);
        assertNotNull("the peer did not answer on the current connection", reply);
        assertEquals(Tuple.of(Atom.of("echo"), message), reply);
    }

    private static Term awaitMessage(Process process, long timeoutMillis)
        throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMillis;
        while (!process.hasMessage() && System.currentTimeMillis() < deadline) {
            Thread.sleep(10);
        }
        assertTrue("the peer did not answer", process.hasMessage());
        Term message = process.nextMessage();
        process.removeMessage();
        return message;
    }

    private void await(String what, long timeoutMillis, BooleanSupplier condition)
        throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMillis;
        while (System.currentTimeMillis() < deadline) {
            if (condition.getAsBoolean()) {
                return;
            }
            Thread.sleep(20);
        }
        assertTrue(what + ", peer output: " + peerOutput(), condition.getAsBoolean());
    }

    /**
     * The tail of what the peer printed, so a failing test says why the peer
     * was not there instead of only that it was not.
     */
    private String peerOutput() {
        try {
            String text = Files.readString(peerLog);
            return text.length() <= 600 ? text : text.substring(text.length() - 600);
        } catch (IOException e) {
            return "(no peer output)";
        }
    }

    /**
     * The erl to start the peer with: the one under -Djerlang.otp.dir when the
     * OTP-backed test job sets it, otherwise the one on the PATH. Null when
     * neither exists, which skips the test.
     */
    private static String erlCommand() {
        String otpDir = System.getProperty("jerlang.otp.dir");
        if (otpDir != null) {
            Path bin = Paths.get(otpDir, "bin");
            for (String name : new String[] {"erl", "erl.exe"}) {
                Path candidate = bin.resolve(name);
                if (Files.isExecutable(candidate)) {
                    return candidate.toString();
                }
            }
        }
        if (runs("erl")) {
            return "erl";
        }
        return null;
    }

    private static boolean runs(String command) {
        try {
            java.lang.Process probe = new ProcessBuilder(command, "-noshell", "-eval", "halt().")
                .redirectErrorStream(true)
                .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                .start();
            return probe.waitFor(30, TimeUnit.SECONDS) && probe.exitValue() == 0;
        } catch (IOException e) {
            return false;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

}
