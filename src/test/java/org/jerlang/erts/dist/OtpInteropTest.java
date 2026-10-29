package org.jerlang.erts.dist;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;

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
 *   erl -name peer@127.0.0.1 -setcookie dist-test-cookie -noshell \
 *     -pa src/test/resources -eval \
 *     'register(echo, spawn(fun Loop() -> receive {From, Msg} ->
 *        From ! {echo, Msg}, Loop() end end)),
 *      P = open_port({spawn, "cmd"}, []),
 *      register(port_ping, spawn(fun Loop() -> receive
 *        {From, port_request} -> From ! {port, P}, Loop();
 *        {From, {port_echo, Q}} ->
 *          From ! {port_same, is_port(Q) andalso Q =:= P}, Loop()
 *      end end)),
 *      register(drop, spawn(fun Loop() -> receive
 *        {From, {drop, Node}} -> From ! dropping,
 *          erlang:disconnect_node(Node), Loop()
 *      end end)),
 *      io:format("READY ~p~n", [node()]),
 *      receive after infinity -> ok end.'
 *
 * -pa src/test/resources lets the peer run the funs of otp_live_demo.
 *
 * Restart the peer (or run code:purge/1 followed by code:load_file/1 on it)
 * after recompiling otp_live_demo.erl. A peer that still has an older version
 * in memory rejects the funs this node sends with badfun, because the module
 * MD5 in NEW_FUN_EXT no longer matches the version the peer loaded.
 *
 * Then run:
 *
 *   mvn test -Dtest=OtpInteropTest -Djerlang.dist.otp=true
 *
 * Without -Djerlang.dist.otp=true the test is skipped. Pass
 * -Djerlang.dist.otp.peer=name@host to use a peer under another name, and
 * -Djerlang.dist.otp.local=name@host to name this node differently.
 *
 * The names must be reachable node names. OTP rejects names whose host
 * part is neither a fully qualified domain name nor an IP address, and
 * silently drops messages that are addressed to such a node, so the
 * default is 127.0.0.1 rather than localhost.
 */
public class OtpInteropTest {

    private static final Atom LOCAL = Atom.of(
        System.getProperty("jerlang.dist.otp.local", "jerlang@127.0.0.1"));
    private static final Atom PEER = Atom.of(
        System.getProperty("jerlang.dist.otp.peer", "peer@127.0.0.1"));
    private static final Path DIST_DEMO = Paths.get("dist_demo.beam");
    private static final Path SPAWN_DEMO = Paths.get("dist_spawn_demo.beam");
    private static final Path LIVE_DEMO = Paths.get("otp_live_demo.beam");

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
        Files.copy(Paths.get("src/test/resources/otp_live_demo.beam"), LIVE_DEMO,
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
            Files.deleteIfExists(LIVE_DEMO);
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

    @Test
    public void rpcCallsCastsAndFailuresReachOtp() throws Throwable {
        assertEquals(Atom.of("pong"), ping());

        assertEquals(PEER, Erlang.apply(Atom.of("otp_live_demo"), Atom.of("rpc_node"),
            List.of(PEER)));
        assertEquals(Integer.of(42),
            Erlang.apply(Atom.of("otp_live_demo"), Atom.of("rpc_add"),
                List.of(PEER, Integer.of(20), Integer.of(22))));

        Term failure = Erlang.apply(Atom.of("otp_live_demo"), Atom.of("rpc_failure"),
            List.of(PEER));
        assertEquals(Atom.of("badrpc"), failure.toTuple().element(1));

        Process sink = sink(Atom.of("rpc_live_sink"), new PID(128));
        Tuple target = Tuple.of(Atom.of("rpc_live_sink"), LOCAL);
        assertEquals(Atom.of("true"),
            Erlang.apply(Atom.of("otp_live_demo"), Atom.of("rpc_cast_notify"),
                List.of(PEER, target)));
        assertEquals(Atom.of("cast_ran"), awaitMessage(sink));
    }

    @Test
    public void spawnsAFunWithFreeVariablesOnOtp() throws Throwable {
        assertEquals(Atom.of("pong"), ping());

        Process sink = sink(Atom.of("fun_live_sink"), new PID(129));
        Tuple target = Tuple.of(Atom.of("fun_live_sink"), LOCAL);
        Tuple result = Erlang.apply(Atom.of("otp_live_demo"), Atom.of("spawn_fun_notify"),
            List.of(PEER, target)).toTuple();

        assertEquals(Atom.of("fun_spawned"), result.element(1));
        assertEquals(PEER, result.element(2).toPID().node());
        assertEquals(Atom.of("remote_fun_ran"), awaitMessage(sink));
    }


    @Test
    public void otpAppliesAFunThatThisNodeSent() throws Throwable {
        assertEquals(Atom.of("pong"), ping());
        assertEquals(Integer.of(42),
            Erlang.apply(Atom.of("otp_live_demo"), Atom.of("send_fun_and_apply"),
                List.of(PEER, Integer.of(42))));
    }

    @Test
    public void appliesAFunThatOtpSentToThisNode() throws Throwable {
        assertEquals(Atom.of("pong"), ping());

        // rpc:call carries the fun back in its reply, and apply_fun runs it
        // here, so the lambda and its free variable survived decoding.
        assertEquals(Tuple.of(Atom.of("applied"), Integer.of(42)),
            Erlang.apply(Atom.of("otp_live_demo"), Atom.of("receive_fun"),
                List.of(PEER)));
    }

    @Test
    public void monitorNodesReportsAConnectionMadeAfterSubscribing() throws Throwable {
        assertEquals(Atom.of("pong"), ping());
        // Start from no connection, so the subscription sees the nodeup.
        assertEquals(Atom.of("true"), Erlang.apply(Atom.of("net_kernel"),
            Atom.of("disconnect_node"), List.of(PEER)));

        Process ready = sink(Atom.of("nodes_ready"), new PID(130));
        Call call = call("monitor_nodes_result", ready.pid());
        awaitMessage(ready);

        assertEquals(Atom.of("true"), Erlang.apply(Atom.of("net_kernel"),
            Atom.of("connect_node"), List.of(PEER)));
        assertEquals(Tuple.of(Atom.of("nodeup"), PEER), call.await());
    }

    @Test
    public void monitorNodeReportsThePeerDroppingTheConnection() throws Throwable {
        assertEquals(Atom.of("pong"), ping());

        Process ready = sink(Atom.of("drop_ready"), new PID(131));
        Call call = call("monitor_node_result", PEER, ready.pid());
        awaitMessage(ready);

        Distribution.send(Tuple.of(Atom.of("drop"), PEER), ready.pid(),
            Tuple.of(ready.pid(), Tuple.of(Atom.of("drop"), LOCAL)));

        assertEquals(Tuple.of(Atom.of("nodedown"), PEER), call.await());
    }

    private static Atom ping() throws Throwable {
        return Erlang.apply(Atom.of("net_adm"), Atom.of("ping"), List.of(PEER)).toAtom();
    }

    private static Process sink(Atom name, PID pid) {
        Process process = new Process(pid);
        ProcessRegistry.register(process);
        ProcessRegistry.register(name, process.id());
        return process;
    }

    private static Call call(String function, Term... args) {
        return new Call(function, args);
    }

    private static final class Call {

        private final FutureTask<Term> task;

        Call(String function, Term... args) {
            this.task = new FutureTask<>(() -> Erlang.apply(Atom.of("otp_live_demo"),
                Atom.of(function), List.of(args)));
            Thread thread = new Thread(task, "otp-live-call-" + function);
            thread.setDaemon(true);
            thread.start();
        }

        Term await() throws Throwable {
            try {
                return task.get(10, TimeUnit.SECONDS);
            } catch (ExecutionException e) {
                throw e.getCause();
            }
        }

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
