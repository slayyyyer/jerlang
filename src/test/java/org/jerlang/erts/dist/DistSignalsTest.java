package org.jerlang.erts.dist;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import org.jerlang.Process;
import org.jerlang.ProcessOrPort;
import org.jerlang.ProcessRegistry;
import org.jerlang.erts.Erlang;
import org.jerlang.erts.etf.ExternalTermReader;
import org.jerlang.erts.etf.ExternalTermWriter;
import org.jerlang.type.Atom;
import org.jerlang.type.Integer;
import org.jerlang.type.List;
import org.jerlang.type.PID;
import org.jerlang.type.Reference;
import org.jerlang.type.Term;
import org.jerlang.type.Tuple;
import org.jerlang.vm.VirtualMachine;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/**
 * Runs src/test/erl/dist_signals_demo.erl, compiled by Erlang/OTP 29,
 * against a fake peer node. The fake peer speaks the version 6 handshake
 * and the 112-tagged distribution format, so links, monitors and exit
 * signals can be checked in both directions without a second Erlang node.
 */
public class DistSignalsTest {

    private static final Atom LOCAL = Atom.of("jerlang@127.0.0.1");
    private static final Atom PEER = Atom.of("peer@127.0.0.1");
    private static final Atom NOBODY = Atom.of("nobody@127.0.0.1");
    private static final PID REMOTE = new PID(PEER, 77, 0, 0);
    private static final String COOKIE = "dist-test-cookie";

    private static final Path MODULE = Paths.get("src/test/resources/dist_signals_demo.beam");
    private static final Path LOCAL_MODULE = Paths.get("dist_signals_demo.beam");

    private VirtualMachine vm;
    private Atom previousNode;
    private int previousCreation;

    private ServerSocket epmd;
    private ServerSocket peerSocket;

    private final BlockingQueue<Peer> peers = new LinkedBlockingQueue<>();
    private volatile Throwable peerFailure;

    @Before
    public void setUp() throws Exception {
        Files.copy(MODULE, LOCAL_MODULE, StandardCopyOption.REPLACE_EXISTING);
        vm = VirtualMachine.instance();
        previousNode = vm.node();
        previousCreation = vm.nodeCreation();
        vm.setNode(LOCAL, 1);
        DistCookie.setCookie(COOKIE);

        epmd = new ServerSocket(0, 4, InetAddress.getByName("127.0.0.1"));
        peerSocket = new ServerSocket(0, 4, InetAddress.getByName("127.0.0.1"));
        System.setProperty("jerlang.epmd.port", String.valueOf(epmd.getLocalPort()));

        start(this::epmdLoop, "fake-epmd");
        start(this::acceptLoop, "fake-peer");
        vm.start();
    }

    @After
    public void tearDown() throws Exception {
        Distribution.disconnectAll();
        vm.shutdown();
        epmd.close();
        peerSocket.close();
        System.clearProperty("jerlang.epmd.port");
        DistCookie.reset();
        vm.setNode(previousNode, previousCreation);
        Files.deleteIfExists(LOCAL_MODULE);
    }

    @Test
    public void monitorNodesReportsANewConnection() throws Throwable {
        Process ready = sink(Atom.of("node_ready"));
        Call call = call("monitor_nodes_wait", ready.pid());
        awaitMailbox(ready.pid());

        Peer peer = connect();

        assertEquals(Tuple.of(Atom.of("nodeup"), PEER), call.await());
        peer.close();
    }

    @Test
    public void monitorNodeReportsEachSubscriptionWhenTheNodeGoesDown() throws Throwable {
        Peer peer = connect();
        Process ready = sink(Atom.of("node_ready"));
        Call call = call("monitor_node_twice_wait", PEER, ready.pid());
        awaitMailbox(ready.pid());

        peer.close();

        assertEquals(Tuple.of(Atom.of("nodedown"), PEER, PEER), call.await());
    }

    @Test
    public void monitorNodeOnAnUnreachableNodeReportsNodedown() throws Throwable {
        assertEquals(Atom.of("nodedown"), call("monitor_node_missing", NOBODY).await());
    }

    @Test
    public void nodesAndIsAliveReportTheDistributionState() throws Throwable {
        Peer peer = connect();

        Tuple subsets = call("nodes_subset").await().toTuple();
        assertEquals(List.of(PEER), subsets.element(1));
        assertEquals(List.of(PEER), subsets.element(2));
        assertEquals(List.nil, subsets.element(3));
        assertEquals(Atom.of("true"), call("is_alive").await());

        peer.close();
    }

    @Test
    public void linkIsSentAndThePeersExitIsTrapped() throws Throwable {
        Call call = call("trapped_link", REMOTE);
        Peer peer = peer();

        Term[] packet = peer.next(3000);
        assertNotNull("no LINK signal was sent", packet);
        Tuple link = packet[0].toTuple();
        assertEquals(Integer.of(1), link.element(1));
        PID local = link.element(2).toPID();
        assertEquals(LOCAL, local.node());
        assertEquals(REMOTE, link.element(3));
        assertNull(packet[1]);

        peer.send(Tuple.of(Integer.of(3), REMOTE, local, Atom.of("boom")));

        assertEquals(Atom.of("ok"), call.await());
        peer.close();
    }

    @Test
    public void unlinkUsesTheUnlinkIdProtocol() throws Throwable {
        Call call = call("unlink_remote", REMOTE);
        Peer peer = peer();

        Term[] linkPacket = peer.next(3000);
        assertNotNull("no LINK signal was sent", linkPacket);
        Tuple link = linkPacket[0].toTuple();
        assertEquals(Integer.of(1), link.element(1));
        PID local = link.element(2).toPID();
        assertEquals(REMOTE, link.element(3));

        Term[] unlinkPacket = peer.next(3000);
        assertNotNull("no UNLINK_ID signal was sent", unlinkPacket);
        Tuple unlink = unlinkPacket[0].toTuple();
        assertEquals(Integer.of(35), unlink.element(1));
        long id = unlink.element(2).toInteger().toLong();
        assertTrue("the unlink id must be positive", id > 0);
        assertEquals(local, unlink.element(3));
        assertEquals(REMOTE, unlink.element(4));

        peer.send(Tuple.of(Integer.of(36), new Integer(id), REMOTE, local));

        assertEquals(Atom.of("ok"), call.await());
        assertNull("a second unlink must stay silent", peer.next(400));
        peer.close();
    }

    @Test
    public void monitorAndDemonitorAreSent() throws Throwable {
        Call call = call("demonitor_remote", REMOTE);
        Peer peer = peer();

        Term[] monitorPacket = peer.next(3000);
        assertNotNull("no MONITOR_P signal was sent", monitorPacket);
        Tuple monitor = monitorPacket[0].toTuple();
        assertEquals(Integer.of(19), monitor.element(1));
        PID local = monitor.element(2).toPID();
        assertEquals(REMOTE, monitor.element(3));
        Reference reference = monitor.element(4).toReference();

        Term[] demonitorPacket = peer.next(3000);
        assertNotNull("no DEMONITOR_P signal was sent", demonitorPacket);
        Tuple demonitor = demonitorPacket[0].toTuple();
        assertEquals(Integer.of(20), demonitor.element(1));
        assertEquals(local, demonitor.element(2));
        assertEquals(REMOTE, demonitor.element(3));
        assertEquals(reference, demonitor.element(4));

        assertEquals(Atom.of("ok"), call.await());
        peer.close();
    }

    @Test
    public void aLateMonitorExitIsIgnoredAfterDemonitor() throws Throwable {
        Call call = call("demonitor_then_wait", REMOTE);
        Peer peer = peer();

        Term[] monitorPacket = peer.next(3000);
        assertNotNull("no MONITOR_P signal was sent", monitorPacket);
        Tuple monitor = monitorPacket[0].toTuple();
        PID local = monitor.element(2).toPID();
        Reference reference = monitor.element(4).toReference();

        Term[] demonitorPacket = peer.next(3000);
        assertNotNull("no DEMONITOR_P signal was sent", demonitorPacket);
        assertEquals(Integer.of(20), demonitorPacket[0].toTuple().element(1));

        // The peer's DOWN crosses the DEMONITOR_P on the wire.
        peer.send(Tuple.of(Integer.of(21), REMOTE, local, reference, Atom.of("boom")));

        assertEquals(Atom.of("ok"), call.await());
        peer.close();
    }

    @Test
    public void monitoringARemoteNameReportsTheNameInDown() throws Throwable {
        Atom name = Atom.of("echo");
        Tuple target = Tuple.of(name, PEER);
        Call call = call("monitor_remote_name", target);
        Peer peer = peer();

        Term[] packet = peer.next(3000);
        assertNotNull("no MONITOR_P signal was sent", packet);
        Tuple monitor = packet[0].toTuple();
        assertEquals(Integer.of(19), monitor.element(1));
        PID local = monitor.element(2).toPID();
        assertEquals(name, monitor.element(3));
        Reference reference = monitor.element(4).toReference();

        peer.send(Tuple.of(Integer.of(21), name, local, reference, Atom.of("noproc")));

        assertEquals(Atom.of("ok"), call.await());
        peer.close();
    }

    @Test
    public void remoteMonitorExitDeliversDown() throws Throwable {
        Call call = call("monitor_remote", REMOTE);
        Peer peer = peer();

        Term[] packet = peer.next(3000);
        assertNotNull("no MONITOR_P signal was sent", packet);
        Tuple monitor = packet[0].toTuple();
        PID local = monitor.element(2).toPID();
        Reference reference = monitor.element(4).toReference();

        peer.send(Tuple.of(Integer.of(21), REMOTE, local, reference, Atom.of("boom")));

        assertEquals(Atom.of("ok"), call.await());
        peer.close();
    }

    @Test
    public void remoteExitSendsExitTwo() throws Throwable {
        Call call = call("remote_exit", REMOTE);
        Peer peer = peer();

        Term[] packet = peer.next(3000);
        assertNotNull("no EXIT2 signal was sent", packet);
        Tuple exit = packet[0].toTuple();
        assertEquals(Integer.of(8), exit.element(1));
        PID local = exit.element(2).toPID();
        assertEquals(REMOTE, exit.element(3));
        assertEquals(Atom.of("die"), exit.element(4));

        assertEquals(Atom.of("ok"), call.await());
        peer.close();
    }

    @Test
    public void incomingLinkAndUnlinkControlTheLocalLink() throws Throwable {
        Peer peer = connect();
        PID waiter = spawnWaiter();

        peer.send(Tuple.of(Integer.of(1), REMOTE, waiter));
        peer.send(Tuple.of(Integer.of(35), Integer.of(7), REMOTE, waiter));

        Term[] packet = peer.next(3000);
        assertNotNull("the UNLINK_ID signal was not acknowledged", packet);
        assertEquals(Tuple.of(Integer.of(36), Integer.of(7), waiter, REMOTE), packet[0]);

        Distribution.deliverLocal(waiter, Atom.of("go"));
        assertNull("an unlinked process must not send an EXIT", peer.next(600));
        peer.close();
    }

    @Test
    public void incomingLinkReportsACrashToThePeer() throws Throwable {
        Peer peer = connect();
        PID waiter = spawnWaiter();

        peer.send(Tuple.of(Integer.of(1), REMOTE, waiter));
        // Signals on one connection are handled in order, so once a message
        // sent after LINK is in the mailbox, the link is in place.
        peer.send(Tuple.of(Integer.of(2), List.nil, waiter), Atom.of("ping"));
        awaitMailbox(waiter);

        Distribution.deliverLocal(waiter, Atom.of("go"));

        Term[] packet = peer.next(3000);
        assertNotNull("the peer did not receive the EXIT signal", packet);
        assertEquals(Tuple.of(Integer.of(3), waiter, REMOTE, Atom.of("boom")), packet[0]);
        peer.close();
    }

    @Test
    public void incomingMonitorReportsDown() throws Throwable {
        Peer peer = connect();
        PID waiter = spawnWaiter();
        Reference reference = new Reference(PEER, 5, 0);

        peer.send(Tuple.of(Integer.of(19), REMOTE, waiter, reference));
        peer.send(Tuple.of(Integer.of(2), List.nil, waiter), Atom.of("ping"));
        awaitMailbox(waiter);

        Distribution.deliverLocal(waiter, Atom.of("go"));

        Term[] packet = peer.next(3000);
        assertNotNull("the peer did not receive MONITOR_P_EXIT", packet);
        assertEquals(Tuple.of(Integer.of(21), waiter, REMOTE, reference, Atom.of("boom")),
            packet[0]);
        peer.close();
    }

    @Test
    public void incomingMonitorOfAnUncaughtErrorCarriesTheStacktrace() throws Throwable {
        Peer peer = connect();
        PID crasher = Erlang.apply(Atom.of("dist_signals_demo"),
            Atom.of("spawn_crasher"), List.nil).toPID();
        Reference reference = new Reference(PEER, 11, 0);

        peer.send(Tuple.of(Integer.of(19), REMOTE, crasher, reference));
        peer.send(Tuple.of(Integer.of(2), List.nil, crasher), Atom.of("ping"));
        awaitMailbox(crasher);

        Distribution.deliverLocal(crasher, Atom.of("go"));

        Term[] packet = peer.next(3000);
        assertNotNull("the peer did not receive MONITOR_P_EXIT", packet);
        Tuple exit = packet[0].toTuple();
        assertEquals(Integer.of(21), exit.element(1));
        assertEquals(crasher, exit.element(2));
        assertEquals(REMOTE, exit.element(3));
        assertEquals(reference, exit.element(4));

        // OTP reports an uncaught error as {Reason, Stacktrace}.
        Tuple reason = exit.element(5).toTuple();
        assertEquals(Atom.of("remote_boom"), reason.element(1));
        List frames = reason.element(2).toList();
        assertFalse("empty stacktrace", frames.length() == 0);
        Tuple frame = frames.head().toTuple();
        assertEquals(Atom.of("dist_signals_demo"), frame.element(1));
        assertEquals(Atom.of("crash_after_go"), frame.element(2));
        peer.close();
    }

    @Test
    public void linkingToADeadLocalProcessAnswersNoproc() throws Throwable {
        Peer peer = connect();
        PID dead = new PID(LOCAL, 999999, 0, 1);

        peer.send(Tuple.of(Integer.of(1), REMOTE, dead));

        Term[] packet = peer.next(3000);
        assertNotNull("the peer did not receive an EXIT signal", packet);
        assertEquals(Tuple.of(Integer.of(3), dead, REMOTE, Atom.of("noproc")), packet[0]);
        peer.close();
    }

    @Test
    public void monitoringADeadLocalProcessAnswersNoproc() throws Throwable {
        Peer peer = connect();
        PID dead = new PID(LOCAL, 999999, 0, 1);
        Reference reference = new Reference(PEER, 9, 0);

        peer.send(Tuple.of(Integer.of(19), REMOTE, dead, reference));

        Term[] packet = peer.next(3000);
        assertNotNull("the peer did not receive MONITOR_P_EXIT", packet);
        assertEquals(Tuple.of(Integer.of(21), dead, REMOTE, reference, Atom.of("noproc")),
            packet[0]);
        peer.close();
    }

    @Test
    public void outgoingRpcCallUsesTheErpcSpawnProtocol() throws Throwable {
        Peer peer = connect();
        Call call = call("rpc_call", PEER, Atom.of("erlang"), Atom.of("node"), List.nil);

        Term[] packet = peer.next(3000);
        assertNotNull("no SPAWN_REQUEST was sent", packet);
        Tuple request = packet[0].toTuple();
        assertEquals(Integer.of(29), request.element(1));
        Reference requestId = request.element(2).toReference();
        PID local = request.element(3).toPID();
        assertEquals(LOCAL, local.node());
        assertEquals(local, request.element(4));
        Tuple mfa = request.element(5).toTuple();
        assertEquals(Atom.of("erpc"), mfa.element(1));
        assertEquals(Atom.of("execute_call"), mfa.element(2));
        assertEquals(Integer.of(4), mfa.element(3));
        assertEquals(List.of(Atom.of("monitor")), request.element(6));

        List args = packet[1].toList();
        assertEquals(4, args.length());
        Reference resultReference = args.head().toReference();
        assertEquals(Atom.of("erlang"), args.tail().head());
        assertEquals(Atom.of("node"), args.tail().tail().head());
        assertEquals(List.nil, args.tail().tail().tail().head());

        peer.send(Tuple.of(Integer.of(31), requestId, local, Integer.of(2), REMOTE));
        // The spawn request id is also the monitor reference, while the
        // result reference lives inside the DOWN reason.
        peer.send(Tuple.of(Integer.of(21), REMOTE, local, requestId,
            Tuple.of(resultReference, Atom.of("return"), PEER)));

        assertEquals(PEER, call.await());
        peer.close();
    }

    @Test
    public void outgoingRpcErrorBecomesBadrpc() throws Throwable {
        Peer peer = connect();
        Call call = call("rpc_call", PEER, Atom.of("bad"), Atom.of("call"), List.nil);

        Term[] packet = peer.next(3000);
        assertNotNull("no SPAWN_REQUEST was sent", packet);
        Tuple request = packet[0].toTuple();
        Reference requestId = request.element(2).toReference();
        PID local = request.element(3).toPID();
        Reference resultReference = packet[1].toList().head().toReference();

        peer.send(Tuple.of(Integer.of(31), requestId, local, Integer.of(2), REMOTE));
        peer.send(Tuple.of(Integer.of(21), REMOTE, local, requestId,
            Tuple.of(resultReference, Atom.of("error"), Atom.of("boom"), List.nil)));

        Tuple result = call.await().toTuple();
        assertEquals(Atom.of("badrpc"), result.element(1));
        Tuple exit = result.element(2).toTuple();
        assertEquals(Atom.of("EXIT"), exit.element(1));
        Tuple reason = exit.element(2).toTuple();
        assertEquals(Atom.of("boom"), reason.element(1));
        assertEquals(List.nil, reason.element(2));
        peer.close();
    }

    @Test
    public void outgoingRpcCastUsesExecuteCastWithoutAReply() throws Throwable {
        Peer peer = connect();
        assertEquals(Atom.of("true"), call("rpc_cast", PEER, Atom.of("dist_signals_demo"),
            Atom.of("rpc_target"), List.nil).await());

        Term[] packet = peer.next(3000);
        assertNotNull("no SPAWN_REQUEST was sent", packet);
        Tuple request = packet[0].toTuple();
        assertEquals(Integer.of(29), request.element(1));
        Tuple mfa = request.element(5).toTuple();
        assertEquals(Atom.of("dist_signals_demo"), mfa.element(1));
        assertEquals(Atom.of("rpc_target"), mfa.element(2));
        assertEquals(Integer.of(0), mfa.element(3));
        assertEquals(List.of(Tuple.of(Atom.of("reply"), Atom.of("no"))),
            request.element(6));
        assertEquals(List.nil, packet[1]);

        assertNull("rpc:cast must not wait for a spawn reply", peer.next(500));
        peer.close();
    }

    @Test
    public void incomingOtpRpcCastRunsLocally() throws Throwable {
        Process sink = sink(Atom.of("rpc_sink"));
        Peer peer = connect();
        Reference requestId = new Reference(PEER, 33, 0);
        Tuple mfa = Tuple.of(Atom.of("erpc"), Atom.of("execute_cast"), Integer.of(3));
        List args = List.of(Atom.of("dist_signals_demo"), Atom.of("rpc_cast_target"),
            List.nil);
        peer.send(Tuple.of(Integer.of(29), requestId, REMOTE, REMOTE, mfa,
            List.of(Tuple.of(Atom.of("reply"), Atom.of("no")))), args);

        awaitMailbox(sink.pid());
        assertEquals(Atom.of("cast_done"), sink.nextMessage());
        assertNull("reply:no must suppress the spawn reply", peer.next(400));
        peer.close();
    }

    @Test
    public void outgoingRpcCallToAnUnreachableNodeIsNodedown() throws Throwable {
        assertEquals(Tuple.of(Atom.of("badrpc"), Atom.of("nodedown")),
            call("rpc_call", NOBODY, Atom.of("erlang"), Atom.of("node"), List.nil).await());
    }

    @Test
    public void incomingOtpRpcCallRunsLocallyAndReplies() throws Throwable {
        Peer peer = connect();
        Reference requestId = new Reference(PEER, 31, 0);
        Reference resultReference = new Reference(PEER, 32, 0);
        Tuple mfa = Tuple.of(Atom.of("erpc"), Atom.of("execute_call"), Integer.of(4));
        List args = List.of(resultReference, Atom.of("dist_signals_demo"),
            Atom.of("rpc_target"), List.nil);
        // OTP's erpc asks for no successful SPAWN_REPLY; the monitor
        // carries the result.
        peer.send(Tuple.of(Integer.of(29), requestId, REMOTE, REMOTE, mfa,
            List.of(Atom.of("monitor"),
                Tuple.of(Atom.of("reply"), Atom.of("error_only")))), args);

        Term[] down = peer.next(3000);
        assertNotNull("the OTP rpc result was not reported", down);
        Tuple signal = down[0].toTuple();
        assertEquals(Integer.of(21), signal.element(1));
        PID child = signal.element(2).toPID();
        assertEquals(LOCAL, child.node());
        assertEquals(REMOTE, signal.element(3));
        assertEquals(requestId, signal.element(4));
        assertEquals(Tuple.of(resultReference, Atom.of("return"), Integer.of(42)),
            signal.element(5));
        assertNull("error_only must suppress the successful spawn reply", peer.next(400));
        peer.close();
    }

    @Test
    public void linkToAnUnreachableNodeReportsNoconnection() throws Throwable {
        PID remote = new PID(NOBODY, 5, 0, 0);
        Call call = call("link_unreachable", remote);
        assertEquals(Atom.of("ok"), call.await());
        assertNull(peerFailure);
    }

    @Test
    public void lostConnectionFailsLinks() throws Throwable {
        Call call = call("noconnection_link", REMOTE);
        Peer peer = peer();

        Term[] packet = peer.next(3000);
        assertNotNull("no LINK signal was sent", packet);
        assertEquals(Integer.of(1), packet[0].toTuple().element(1));

        peer.close();
        assertEquals(Atom.of("ok"), call.await());
    }

    @Test
    public void lostConnectionFailsMonitors() throws Throwable {
        Call call = call("noconnection_monitor", REMOTE);
        Peer peer = peer();

        Term[] packet = peer.next(3000);
        assertNotNull("no MONITOR_P signal was sent", packet);
        assertEquals(Integer.of(19), packet[0].toTuple().element(1));

        peer.close();
        assertEquals(Atom.of("ok"), call.await());
    }

    @Test
    public void aSilentPeerIsDroppedAfterTheTickTimeout() throws Throwable {
        System.setProperty("jerlang.dist.ticktime", "1");
        try {
            Call call = call("noconnection_monitor", REMOTE);
            Peer peer = peer();

            Term[] packet = peer.next(3000);
            assertNotNull("no MONITOR_P signal was sent", packet);
            assertEquals(Integer.of(19), packet[0].toTuple().element(1));

            // The fake peer stays connected but never sends a tick or any
            // other packet, so only the tick timeout can fail the monitor.
            assertEquals(Atom.of("ok"), call.await());
            peer.close();
        } finally {
            System.clearProperty("jerlang.dist.ticktime");
        }
    }

    private Peer connect() throws Exception {
        assertTrue("the node could not connect to the fake peer", Distribution.connect(PEER));
        return peer();
    }

    private Peer peer() throws InterruptedException {
        Peer peer = peers.poll(5, TimeUnit.SECONDS);
        assertNotNull("the node did not connect to the fake peer", peer);
        return peer;
    }

    private PID spawnWaiter() throws Throwable {
        return Erlang.apply(Atom.of("dist_signals_demo"), Atom.of("spawn_waiter"), List.nil)
            .toPID();
    }

    /**
     * A process that only serves as the target of a ready message. It is
     * registered so a test can wait until the Erlang process has installed
     * its node subscription.
     */
    private Process sink(Atom name) {
        Process process = new Process(new PID(LOCAL, 900000, 0, 1));
        ProcessRegistry.register(process);
        ProcessRegistry.register(name, process.id());
        return process;
    }

    private static void awaitMailbox(PID pid) throws InterruptedException {
        ProcessOrPort process = ProcessRegistry.resolve(pid);
        assertTrue("the waiter is gone", process instanceof Process);
        Process waiter = (Process) process;
        long deadline = System.currentTimeMillis() + 3000;
        while (!waiter.hasMessage() && System.currentTimeMillis() < deadline) {
            Thread.sleep(5);
        }
        assertTrue("the peer's message never arrived", waiter.hasMessage());
    }

    private Call call(String function, Term... args) {
        Call call = new Call(function, args);
        call.thread.start();
        return call;
    }

    private final class Call {

        private final FutureTask<Term> task;
        private final Thread thread;

        Call(String function, Term... args) {
            this.task = new FutureTask<>(() -> Erlang.apply(Atom.of("dist_signals_demo"),
                Atom.of(function), List.of(args)));
            this.thread = new Thread(task, "dist-call-" + function);
            this.thread.setDaemon(true);
        }

        Term await() throws Throwable {
            try {
                Term result = task.get(10, TimeUnit.SECONDS);
                assertNull("the fake peer failed: " + peerFailure, peerFailure);
                return result;
            } catch (ExecutionException e) {
                throw e.getCause();
            }
        }

    }

    private void epmdLoop() {
        try {
            while (!epmd.isClosed()) {
                try (Socket socket = epmd.accept()) {
                    socket.setSoTimeout(5000);
                    DataInputStream in = new DataInputStream(socket.getInputStream());
                    int length = in.readUnsignedShort();
                    byte[] request = new byte[length];
                    in.readFully(request);
                    String requested = new String(request, 1, length - 1, StandardCharsets.UTF_8);

                    ByteArrayOutputStream buffer = new ByteArrayOutputStream();
                    DataOutputStream response = new DataOutputStream(buffer);
                    response.writeByte('w');
                    if (!requested.equals("peer")) {
                        // No such node.
                        response.writeByte(1);
                    } else {
                        response.writeByte(0);
                        response.writeShort(peerSocket.getLocalPort());
                        response.writeByte(77); // normal Erlang node
                        response.writeByte(0); // TCP
                        response.writeShort(6);
                        response.writeShort(6);
                        response.writeShort(requested.length());
                        response.write(requested.getBytes(StandardCharsets.UTF_8));
                        response.writeShort(0);
                    }
                    // PORT2_RESP is not length framed; EPMD closes the socket
                    // after writing it.
                    DataOutputStream out = new DataOutputStream(socket.getOutputStream());
                    out.write(buffer.toByteArray());
                    out.flush();
                }
            }
        } catch (IOException e) {
            if (!epmd.isClosed()) {
                peerFailure = e;
            }
        }
    }

    private void acceptLoop() {
        try {
            while (!peerSocket.isClosed()) {
                Peer peer = new Peer(peerSocket.accept());
                peer.handshake(COOKIE);
                peers.add(peer);
            }
        } catch (IOException e) {
            if (!peerSocket.isClosed()) {
                peerFailure = e;
            }
        }
    }

    private static void start(Runnable runnable, String name) {
        Thread thread = new Thread(runnable, name);
        thread.setDaemon(true);
        thread.start();
    }

    /**
     * The accepting side of the version 6 handshake, plus the 112-tagged
     * message format.
     */
    private static final class Peer implements AutoCloseable {

        private final Socket socket;
        private final DataInputStream in;
        private final DataOutputStream out;

        Peer(Socket socket) throws IOException {
            this.socket = socket;
            socket.setTcpNoDelay(true);
            this.in = new DataInputStream(new BufferedInputStream(socket.getInputStream()));
            this.out = new DataOutputStream(new BufferedOutputStream(socket.getOutputStream()));
        }

        void handshake(String cookie) throws IOException {
            byte[] namePacket = readPacket2(in);
            DataInputStream name = new DataInputStream(new ByteArrayInputStream(namePacket));
            assertEquals(78, name.readUnsignedByte()); // 'N'
            name.readLong(); // client flags
            name.readInt(); // client creation
            readShortString(name); // client node name

            writePacket2(out, "sok".getBytes(StandardCharsets.UTF_8));

            int challenge = 0x12345678;
            ByteArrayOutputStream challengeBuffer = new ByteArrayOutputStream();
            DataOutputStream challengePacket = new DataOutputStream(challengeBuffer);
            challengePacket.writeByte('N');
            challengePacket.writeLong(DistFlags.OURS);
            challengePacket.writeInt(challenge);
            challengePacket.writeInt(0);
            writeShortString(challengePacket, PEER.toString());
            writePacket2(out, challengeBuffer.toByteArray());

            byte[] reply = readPacket2(in);
            DataInputStream replyStream = new DataInputStream(new ByteArrayInputStream(reply));
            assertEquals(114, replyStream.readUnsignedByte()); // 'r'
            int clientChallenge = replyStream.readInt();
            byte[] clientDigest = new byte[16];
            replyStream.readFully(clientDigest);
            if (!MessageDigest.isEqual(digest(challenge, cookie), clientDigest)) {
                throw new IOException("the client did not prove knowledge of the cookie");
            }

            byte[] ack = new byte[17];
            ack[0] = 'a';
            System.arraycopy(digest(clientChallenge, cookie), 0, ack, 1, 16);
            writePacket2(out, ack);
        }

        /**
         * The next control message, or null when nothing arrives within
         * the timeout. The second element is the message term of a
         * SEND/REG_SEND signal, and null for the other signals.
         */
        Term[] next(int timeoutMillis) throws Throwable {
            socket.setSoTimeout(timeoutMillis);
            try {
                while (true) {
                    int length = in.readInt();
                    if (length == 0) {
                        // A tick.
                        continue;
                    }
                    byte[] payload = new byte[length];
                    in.readFully(payload);
                    DataInputStream terms = new DataInputStream(
                        new ByteArrayInputStream(payload, 1, payload.length - 1));
                    Term control = new ExternalTermReader(terms).read();
                    Term message = terms.available() > 0
                        ? new ExternalTermReader(terms).read()
                        : null;
                    return new Term[] { control, message };
                }
            } catch (SocketTimeoutException e) {
                return null;
            }
        }

        void send(Term control) throws IOException {
            send(control, null);
        }

        void send(Term control, Term message) throws IOException {
            ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            DataOutputStream packet = new DataOutputStream(buffer);
            packet.writeByte(112);
            packet.write(ExternalTermWriter.encode(control).toByteArray());
            if (message != null) {
                packet.write(ExternalTermWriter.encode(message).toByteArray());
            }
            out.writeInt(buffer.size());
            out.write(buffer.toByteArray());
            out.flush();
        }

        @Override
        public void close() throws IOException {
            socket.close();
        }

        private static byte[] digest(int challenge, String cookie) throws IOException {
            try {
                MessageDigest md5 = MessageDigest.getInstance("MD5");
                return md5.digest((cookie + java.lang.Integer.toUnsignedString(challenge))
                    .getBytes(StandardCharsets.UTF_8));
            } catch (java.security.NoSuchAlgorithmException e) {
                throw new IOException("MD5 is not available", e);
            }
        }

        private static byte[] readPacket2(DataInputStream in) throws IOException {
            int length = in.readUnsignedShort();
            byte[] payload = new byte[length];
            in.readFully(payload);
            return payload;
        }

        private static void writePacket2(DataOutputStream out, byte[] payload) throws IOException {
            out.writeShort(payload.length);
            out.write(payload);
            out.flush();
        }

        private static void writeShortString(DataOutputStream out, String string)
            throws IOException {
            byte[] bytes = string.getBytes(StandardCharsets.UTF_8);
            out.writeShort(bytes.length);
            out.write(bytes);
        }

        private static String readShortString(DataInputStream in) throws IOException {
            int length = in.readUnsignedShort();
            byte[] bytes = new byte[length];
            in.readFully(bytes);
            return new String(bytes, StandardCharsets.UTF_8);
        }

    }

}
