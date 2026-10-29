package org.jerlang.erts.dist;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;

import org.jerlang.Process;
import org.jerlang.ProcessRegistry;
import org.jerlang.erts.Erlang;
import org.jerlang.type.Atom;
import org.jerlang.type.Integer;
import org.jerlang.type.List;
import org.jerlang.type.PID;
import org.jerlang.type.PortID;
import org.jerlang.type.Term;
import org.jerlang.type.Tuple;
import org.jerlang.vm.VirtualMachine;
import org.junit.After;
import org.junit.Assume;
import org.junit.Before;
import org.junit.Test;

/**
 * Opt-in test against a real Erlang/OTP node.
 *
 * Start the peer with:
 *
 *   erl -name peer@localhost -setcookie dist-test-cookie -noshell -eval \
 *     'register(echo, spawn(fun Loop() -> receive {From, Msg} ->
 *        From ! {echo, Msg}, Loop() end end)),
 *      P = open_port({spawn, "cmd"}, []),
 *      register(port_ping, spawn(fun Loop() -> receive
 *        {From, port_request} -> From ! {port, P}, Loop();
 *        {From, {port_echo, Q}} ->
 *          From ! {port_same, is_port(Q) andalso Q =:= P}, Loop()
 *      end end)),
 *      io:format("READY ~p~n", [node()]),
 *      receive after infinity -> ok end.'
 *
 * Then run:
 *
 *   mvn test -Dtest=OtpInteropTest -Djerlang.dist.otp=true
 *
 * Without -Djerlang.dist.otp=true the test is skipped. Pass
 * -Djerlang.dist.otp.peer=name@host to use a peer under another name.
 */
public class OtpInteropTest {

    private static final Atom LOCAL = Atom.of("jerlang@localhost");
    private static final Atom PEER = Atom.of(
        System.getProperty("jerlang.dist.otp.peer", "peer@localhost"));
    private static final Path DIST_DEMO = Paths.get("dist_demo.beam");
    private static final Path SPAWN_DEMO = Paths.get("dist_spawn_demo.beam");

    private VirtualMachine vm;
    private Atom previousNode;
    private int previousCreation;
    private boolean started;

    @Before
    public void setUp() throws Exception {
        Assume.assumeTrue("set -Djerlang.dist.otp=true to run this test",
            Boolean.getBoolean("jerlang.dist.otp"));

        vm = VirtualMachine.instance();
        previousNode = vm.node();
        previousCreation = vm.nodeCreation();
        vm.setNode(LOCAL, 1);
        DistCookie.setCookie("dist-test-cookie");
        Files.copy(Paths.get("src/test/resources/dist_demo.beam"), DIST_DEMO,
            StandardCopyOption.REPLACE_EXISTING);
        Files.copy(Paths.get("src/test/resources/dist_spawn_demo.beam"), SPAWN_DEMO,
            StandardCopyOption.REPLACE_EXISTING);
        vm.start();
        started = true;
    }

    @After
    public void tearDown() {
        if (!started) {
            return;
        }
        Distribution.disconnectAll();
        vm.shutdown();
        DistCookie.reset();
        vm.setNode(previousNode, previousCreation);
        try {
            Files.deleteIfExists(DIST_DEMO);
            Files.deleteIfExists(SPAWN_DEMO);
        } catch (Exception ignored) {
            // Nothing to clean up if the file was never copied.
        }
    }

    @Test
    public void pingAndExchangeMessagesWithOtp() throws Throwable {
        assertEquals(Atom.of("pong"),
            Erlang.apply(Atom.of("net_adm"), Atom.of("ping"), List.of(PEER)));
        assertTrue(Distribution.isConnected(PEER));
        assertEquals(List.of(PEER), Erlang.nodes());

        Process sink = new Process(new PID(123));
        ProcessRegistry.register(sink);
        ProcessRegistry.register(Atom.of("sink"), sink.id());

        Tuple target = Tuple.of(Atom.of("echo"), PEER);
        Term hello = Tuple.of(Atom.of("hello"), Integer.of(42));
        Distribution.send(target, sink.pid(), Tuple.of(sink.pid(), hello));

        long deadline = System.currentTimeMillis() + 5000;
        while (!sink.hasMessage() && System.currentTimeMillis() < deadline) {
            Thread.sleep(10);
        }
        assertTrue("OTP did not send a reply", sink.hasMessage());
        Term reply = sink.nextMessage();
        assertNotNull(reply);
        assertEquals(Tuple.of(Atom.of("echo"), hello), reply);
    }

    @Test
    public void spawnsAProcessOnOtp() throws Throwable {
        assertEquals(Atom.of("pong"),
            Erlang.apply(Atom.of("net_adm"), Atom.of("ping"), List.of(PEER)));

        Process sink = new Process(new PID(321));
        ProcessRegistry.register(sink);
        ProcessRegistry.register(Atom.of("spawn_sink"), sink.id());

        Term target = Tuple.of(Atom.of("spawn_sink"), LOCAL);
        PID child = Erlang.apply(Atom.of("dist_spawn_demo"), Atom.of("spawn_mfa"),
            List.of(PEER, Atom.of("erlang"), Atom.of("send"),
                List.of(target, Atom.of("from_otp")))).toPID();
        assertEquals(PEER, child.node());

        long deadline = System.currentTimeMillis() + 5000;
        while (!sink.hasMessage() && System.currentTimeMillis() < deadline) {
            Thread.sleep(10);
        }
        assertTrue("the OTP process did not send its message", sink.hasMessage());
        assertEquals(Atom.of("from_otp"), sink.nextMessage());
    }

    @Test
    public void spawnRequestMonitorToOtpReportsDown() throws Throwable {
        assertEquals(Atom.of("pong"),
            Erlang.apply(Atom.of("net_adm"), Atom.of("ping"), List.of(PEER)));

        Term result = Erlang.apply(Atom.of("dist_spawn_demo"), Atom.of("monitor_mfa"),
            List.of(PEER, Atom.of("erlang"), Atom.of("node"), List.nil));
        Tuple down = result.toTuple();
        assertEquals(Atom.of("down"), down.element(1));
        assertEquals(PEER, down.element(3).toPID().node());
        assertEquals(Atom.of("normal"), down.element(4));
    }

    /**
     * OTP sends a port inside a message; we decode it, send it back, and
     * OTP compares it with its own port term. This covers the external
     * term format in both directions on a real connection.
     */
    @Test
    public void carriesPortTermsToAndFromOtp() throws Throwable {
        assertEquals(Atom.of("pong"),
            Erlang.apply(Atom.of("net_adm"), Atom.of("ping"), List.of(PEER)));

        Process sink = new Process(new PID(124));
        ProcessRegistry.register(sink);
        ProcessRegistry.register(Atom.of("port_sink"), sink.id());

        Tuple target = Tuple.of(Atom.of("port_ping"), PEER);
        Distribution.send(target, sink.pid(),
            Tuple.of(sink.pid(), Atom.of("port_request")));

        Tuple reply = awaitMessage(sink).toTuple();
        assertEquals(Atom.of("port"), reply.element(1));
        PortID port = reply.element(2).toPortID();
        assertEquals(PEER, port.node());
        assertFalse(port.isLocal());

        Distribution.send(target, sink.pid(),
            Tuple.of(sink.pid(), Tuple.of(Atom.of("port_echo"), port)));

        Tuple same = awaitMessage(sink).toTuple();
        assertEquals(Atom.of("port_same"), same.element(1));
        assertEquals(Atom.of("true"), same.element(2));
    }

    private static Term awaitMessage(Process process) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 5000;
        while (!process.hasMessage() && System.currentTimeMillis() < deadline) {
            Thread.sleep(10);
        }
        assertTrue("OTP did not send a reply", process.hasMessage());
        Term message = process.nextMessage();
        process.removeMessage();
        return message;
    }

    @Test
    public void compiledErlangCallsAnotherNode() throws Throwable {
        assertEquals(Atom.of("pong"),
            Erlang.apply(Atom.of("net_adm"), Atom.of("ping"), List.of(PEER)));

        Term message = Tuple.of(Atom.of("hello"), Integer.of(42));
        Term reply = Erlang.apply(Atom.of("dist_demo"), Atom.of("echo_roundtrip"),
            List.of(PEER, message));
        assertEquals(message, reply);

        assertEquals(LOCAL,
            Erlang.apply(Atom.of("dist_demo"), Atom.of("node_name"), List.nil));
        assertEquals(List.of(PEER),
            Erlang.apply(Atom.of("dist_demo"), Atom.of("peer_nodes"), List.nil));
    }

}
