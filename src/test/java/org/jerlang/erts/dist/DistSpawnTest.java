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
 * Runs src/test/erl/dist_spawn_demo.erl, compiled by Erlang/OTP 29,
 * against a fake peer node. The fake peer speaks the version 6 handshake
 * and the 112-tagged distribution format, so both directions of remote
 * spawn can be checked without a second Erlang node.
 */
public class DistSpawnTest {

    private static final Atom LOCAL = Atom.of("jerlang@127.0.0.1");
    private static final Atom PEER = Atom.of("peer@127.0.0.1");
    private static final Atom NOBODY = Atom.of("nobody@127.0.0.1");
    private static final PID REMOTE = new PID(PEER, 77, 0, 0);
    private static final String COOKIE = "dist-test-cookie";

    private static final Path MODULE = Paths.get("src/test/resources/dist_spawn_demo.beam");
    private static final Path LOCAL_MODULE = Paths.get("dist_spawn_demo.beam");

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
    public void spawnStartsAProcessOnThePeer() throws Throwable {
        Call call = call("spawn_remote", PEER);
        Peer peer = peer();

        Term[] packet = peer.next(3000);
        assertNotNull("no SPAWN_REQUEST was sent", packet);
        Tuple request = packet[0].toTuple();
        assertEquals(Integer.of(29), request.element(1));
        Reference requestId = request.element(2).toReference();
        PID local = request.element(3).toPID();
        assertEquals(LOCAL, local.node());
        // JErlang has no process groups, so the caller is sent as the
        // group leader of the new process.
        assertEquals(local, request.element(4));
        Tuple mfa = request.element(5).toTuple();
        assertEquals(Atom.of("dist_spawn_demo"), mfa.element(1));
        assertEquals(Atom.of("wait_forever"), mfa.element(2));
        assertEquals(Integer.of(0), mfa.element(3));
        assertEquals(List.nil, request.element(6));
        assertEquals(List.nil, packet[1]);

        peer.send(Tuple.of(Integer.of(31), requestId, local, Integer.of(0), REMOTE));

        assertEquals(REMOTE, call.await());

        Term[] send = peer.next(3000);
        assertNotNull("the returned pid was not usable", send);
        Tuple control = send[0].toTuple();
        assertEquals(Integer.of(2), control.element(1));
        assertEquals(REMOTE, control.element(3));
        assertEquals(Tuple.of(Atom.of("hello"), local), send[1]);
        peer.close();
    }

    @Test
    public void spawnOnAnUnreachableNodeYieldsAUselessPid() throws Throwable {
        Call call = call("spawn_unreachable", NOBODY);
        PID pid = call.await().toPID();
        assertEquals(LOCAL, pid.node());
        assertFalse(ProcessRegistry.isAlive(pid));
    }

    @Test
    public void spawnRequestDeliversTheReplyMessage() throws Throwable {
        Call call = call("request_reply", PEER);
        Peer peer = peer();

        Term[] packet = peer.next(3000);
        assertNotNull("no SPAWN_REQUEST was sent", packet);
        Tuple request = packet[0].toTuple();
        assertEquals(Integer.of(29), request.element(1));
        Reference requestId = request.element(2).toReference();
        PID local = request.element(3).toPID();
        assertEquals(List.nil, request.element(6));

        peer.send(Tuple.of(Integer.of(31), requestId, local, Integer.of(0), REMOTE));

        assertEquals(Tuple.of(Atom.of("ok"), requestId, REMOTE), call.await());
        peer.close();
    }

    @Test
    public void spawnRequestErrorDoesNotCreateALinkOrMonitor() throws Throwable {
        Call call = call("request_error", PEER);
        Peer peer = peer();

        Term[] packet = peer.next(3000);
        assertNotNull("no SPAWN_REQUEST was sent", packet);
        Tuple request = packet[0].toTuple();
        Reference requestId = request.element(2).toReference();
        PID local = request.element(3).toPID();
        assertEquals(List.of(Atom.of("link"), Atom.of("monitor")), request.element(6));

        peer.send(Tuple.of(Integer.of(31), requestId, local, Integer.of(0),
            Atom.of("noconnection")));

        assertEquals(Tuple.of(Atom.of("error"), requestId, Atom.of("noconnection")), call.await());
        assertNull("a failed request must not send a signal", peer.next(400));
        peer.close();
    }

    @Test
    public void spawnRequestToAnUnreachableNodeFails() throws Throwable {
        assertEquals(Tuple.of(Atom.of("error"), Atom.of("noconnection")),
            call("request_unreachable", NOBODY).await());
    }

    @Test
    public void spawnRequestMonitorReportsDown() throws Throwable {
        Call call = call("request_monitored", PEER);
        Peer peer = peer();

        Term[] packet = peer.next(3000);
        assertNotNull("no SPAWN_REQUEST was sent", packet);
        Tuple request = packet[0].toTuple();
        Reference requestId = request.element(2).toReference();
        PID local = request.element(3).toPID();

        peer.send(Tuple.of(Integer.of(31), requestId, local, Integer.of(2), REMOTE));
        peer.send(Tuple.of(Integer.of(21), REMOTE, local, requestId, Atom.of("boom")));

        assertEquals(Tuple.of(Atom.of("down"), requestId, REMOTE, Atom.of("boom")), call.await());
        peer.close();
    }

    @Test
    public void spawnRequestLinkDeliversExit() throws Throwable {
        Call call = call("request_linked", PEER);
        Peer peer = peer();

        Term[] packet = peer.next(3000);
        assertNotNull("no SPAWN_REQUEST was sent", packet);
        Tuple request = packet[0].toTuple();
        Reference requestId = request.element(2).toReference();
        PID local = request.element(3).toPID();

        peer.send(Tuple.of(Integer.of(31), requestId, local, Integer.of(1), REMOTE));
        peer.send(Tuple.of(Integer.of(3), REMOTE, local, Atom.of("boom")));

        assertEquals(Tuple.of(Atom.of("exit"), requestId, REMOTE, Atom.of("boom")), call.await());
        peer.close();
    }

    @Test
    public void abandonedSpawnRequestDropsItsLateReply() throws Throwable {
        Call call = call("request_abandon", PEER);
        Peer peer = peer();

        Term[] packet = peer.next(3000);
        assertNotNull("no SPAWN_REQUEST was sent", packet);
        Tuple request = packet[0].toTuple();
        Reference requestId = request.element(2).toReference();
        PID local = request.element(3).toPID();

        // The BIF returns before the peer can answer, so the request is
        // already abandoned when this reply arrives.
        Thread.sleep(150);
        peer.send(Tuple.of(Integer.of(31), requestId, local, Integer.of(0), REMOTE));

        assertEquals(Tuple.of(Atom.of("true"), Atom.of("false"), Atom.of("no_reply")),
            call.await());
        peer.close();
    }

    @Test
    public void spawnRequestToTheLocalNodeUsesTheSameReply() throws Throwable {
        Tuple result = call("request_local").await().toTuple();

        Tuple reply = result.element(1).toTuple();
        assertEquals(Atom.of("ok"), reply.element(1));
        PID child = reply.element(2).toPID();
        assertEquals(LOCAL, child.node());

        assertEquals(Tuple.of(Atom.of("notified"), child), result.element(2));
        assertEquals(Tuple.of(Atom.of("down"), child, Atom.of("normal")), result.element(3));
    }

    @Test
    public void incomingSpawnRequestStartsALocalProcess() throws Throwable {
        Call sink = call("sink");
        waitForName(Atom.of("spawn_test_sink"));
        Peer peer = connect();

        Reference requestId = new Reference(PEER, 21, 0);
        Tuple mfa = Tuple.of(Atom.of("dist_spawn_demo"), Atom.of("spawn_notify"), Integer.of(0));
        peer.send(Tuple.of(Integer.of(29), requestId, REMOTE, REMOTE, mfa, List.nil), List.nil);

        Term[] packet = peer.next(3000);
        assertNotNull("the spawn request was not answered", packet);
        Tuple reply = packet[0].toTuple();
        assertEquals(Integer.of(31), reply.element(1));
        assertEquals(requestId, reply.element(2));
        assertEquals(REMOTE, reply.element(3));
        assertEquals(Integer.of(0), reply.element(4));
        PID child = reply.element(5).toPID();
        assertEquals(LOCAL, child.node());

        assertEquals(child, sink.await());
        peer.close();
    }

    @Test
    public void incomingSpawnReplyPrecedesTheLinkedExit() throws Throwable {
        Peer peer = connect();

        Reference requestId = new Reference(PEER, 22, 0);
        Tuple mfa = Tuple.of(Atom.of("dist_spawn_demo"), Atom.of("crash"), Integer.of(0));
        peer.send(Tuple.of(Integer.of(29), requestId, REMOTE, REMOTE, mfa,
            List.of(Atom.of("link"))), List.nil);

        Term[] replyPacket = peer.next(3000);
        assertNotNull("the spawn request was not answered", replyPacket);
        Tuple reply = replyPacket[0].toTuple();
        assertEquals(Integer.of(31), reply.element(1));
        assertEquals(Integer.of(1), reply.element(4));
        PID child = reply.element(5).toPID();
        assertEquals(LOCAL, child.node());

        Term[] exitPacket = peer.next(3000);
        assertNotNull("the linked process did not report its exit", exitPacket);
        assertEquals(Tuple.of(Integer.of(3), child, REMOTE, Atom.of("boom")), exitPacket[0]);
        peer.close();
    }

    @Test
    public void incomingSpawnWithMonitorReportsDown() throws Throwable {
        Peer peer = connect();

        Reference requestId = new Reference(PEER, 23, 0);
        Tuple mfa = Tuple.of(Atom.of("dist_spawn_demo"), Atom.of("wait_forever"), Integer.of(0));
        peer.send(Tuple.of(Integer.of(29), requestId, REMOTE, REMOTE, mfa,
            List.of(Atom.of("monitor"))), List.nil);

        Term[] packet = peer.next(3000);
        assertNotNull("the spawn request was not answered", packet);
        Tuple reply = packet[0].toTuple();
        assertEquals(Integer.of(2), reply.element(4));
        PID child = reply.element(5).toPID();

        peer.send(Tuple.of(Integer.of(8), REMOTE, child, Atom.of("kill")));

        Term[] down = peer.next(3000);
        assertNotNull("the monitor did not report the exit", down);
        assertEquals(Tuple.of(Integer.of(21), child, REMOTE, requestId, Atom.of("killed")),
            down[0]);
        peer.close();
    }

    private void waitForName(Atom name) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 3000;
        while (ProcessRegistry.instance().get(name) == null
            && System.currentTimeMillis() < deadline) {
            Thread.sleep(5);
        }
        assertNotNull("the sink never registered itself", ProcessRegistry.instance().get(name));
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

    private Call call(String function, Term... args) {
        Call call = new Call(function, args);
        call.thread.start();
        return call;
    }

    private final class Call {

        private final FutureTask<Term> task;
        private final Thread thread;

        Call(String function, Term... args) {
            this.task = new FutureTask<>(() -> Erlang.apply(Atom.of("dist_spawn_demo"),
                Atom.of(function), List.of(args)));
            this.thread = new Thread(task, "dist-spawn-call-" + function);
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

        Term[] next(int timeoutMillis) throws Throwable {
            socket.setSoTimeout(timeoutMillis);
            try {
                while (true) {
                    int length = in.readInt();
                    if (length == 0) {
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

        private static String readShortString(DataInputStream in) throws IOException {
            int length = in.readUnsignedShort();
            byte[] bytes = new byte[length];
            in.readFully(bytes);
            return new String(bytes, StandardCharsets.UTF_8);
        }

        private static void writeShortString(DataOutputStream out, String string)
            throws IOException {
            byte[] bytes = string.getBytes(StandardCharsets.UTF_8);
            out.writeShort(bytes.length);
            out.write(bytes);
        }

    }

}
