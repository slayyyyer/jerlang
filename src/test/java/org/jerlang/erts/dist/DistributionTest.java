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
import java.io.EOFException;
import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import org.jerlang.Process;
import org.jerlang.ProcessRegistry;
import org.jerlang.erts.Erlang;
import org.jerlang.erts.etf.ExternalTermReader;
import org.jerlang.type.Atom;
import org.jerlang.type.Integer;
import org.jerlang.type.List;
import org.jerlang.type.PID;
import org.jerlang.type.Term;
import org.jerlang.type.Tuple;
import org.jerlang.vm.VirtualMachine;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/**
 * Exercises the distribution client against a fake EPMD and a fake peer
 * that speaks the OTP 23+ handshake and the 112-tagged message format.
 */
public class DistributionTest {

    private static final Atom LOCAL = Atom.of("jerlang@127.0.0.1");
    private static final Atom PEER = Atom.of("peer@127.0.0.1");
    private static final String COOKIE = "dist-test-cookie";

    private VirtualMachine vm;
    private Atom previousNode;
    private int previousCreation;

    private ServerSocket epmd;
    private ServerSocket peerSocket;

    private final BlockingQueue<Term[]> received = new LinkedBlockingQueue<>();
    private volatile String peerCookie = COOKIE;
    private volatile String clientName;
    private volatile int clientCreation;
    private volatile long clientFlags;
    private volatile Throwable peerFailure;

    private Process sink;

    @Before
    public void setUp() throws Exception {
        vm = VirtualMachine.instance();
        previousNode = vm.node();
        previousCreation = vm.nodeCreation();
        vm.setNode(LOCAL, 1);
        DistCookie.setCookie(COOKIE);

        epmd = new ServerSocket(0, 4, InetAddress.getByName("127.0.0.1"));
        peerSocket = new ServerSocket(0, 4, InetAddress.getByName("127.0.0.1"));
        System.setProperty("jerlang.epmd.port", String.valueOf(epmd.getLocalPort()));

        startThread(this::epmdLoop, "fake-epmd");
        startThread(this::peerLoop, "fake-peer");

        vm.start();

        sink = new Process(new PID(123));
        ProcessRegistry.register(sink);
        ProcessRegistry.register(Atom.of("sink"), sink.id());
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
    }

    @Test
    public void pingPerformsTheHandshakeAndConnects() throws Throwable {
        Atom result = Erlang.apply(Atom.of("net_adm"), Atom.of("ping"), List.of(PEER))
            .toAtom();

        assertEquals(Atom.of("pong"), result);
        assertNull("fake peer failed: " + peerFailure, peerFailure);
        assertEquals(LOCAL.toString(), clientName);
        assertEquals(1, clientCreation);
        assertTrue("mandatory flags are missing", (clientFlags & DistFlags.MANDATORY) == DistFlags.MANDATORY);
        assertTrue("the version 6 handshake flag is missing",
            (clientFlags & DistFlags.HANDSHAKE_23) != 0);
        assertEquals(0, clientFlags & DistFlags.DIST_HDR_ATOM_CACHE);

        assertEquals(List.of(PEER), Erlang.nodes());
        assertTrue(Distribution.isConnected(PEER));
    }

    @Test
    public void messagesAreDeliveredInBothDirections() throws Throwable {
        assertEquals(Atom.of("pong"),
            Erlang.apply(Atom.of("net_adm"), Atom.of("ping"), List.of(PEER)));

        // The fake peer sent a REG_SEND and a SEND right after the handshake.
        Term byName = awaitMessage(sink);
        assertEquals(Tuple.of(Atom.of("incoming"), Integer.of(1)), byName);

        Term byPid = awaitMessage(sink);
        assertEquals(Atom.of("hello-from-peer"), byPid);

        // Send to a registered name on the peer, then to a PID.
        Tuple echo = Tuple.of(Atom.of("echo"), PEER);
        Term hello = Tuple.of(Atom.of("hello"), Integer.of(2));
        Distribution.send(echo, sink.pid(), hello);

        Term[] namePacket = received.poll(3, TimeUnit.SECONDS);
        assertNotNull("the peer did not receive the REG_SEND", namePacket);
        assertEquals(Tuple.of(Integer.of(6), sink.pid(), List.nil, Atom.of("echo")),
            namePacket[0]);
        assertEquals(hello, namePacket[1]);

        PID remote = new PID(PEER, 55, 0, 0);
        Distribution.send(remote, sink.pid(), Atom.of("direct"));

        Term[] pidPacket = received.poll(3, TimeUnit.SECONDS);
        assertNotNull("the peer did not receive the SEND", pidPacket);
        assertEquals(Tuple.of(Integer.of(2), List.nil, remote), pidPacket[0]);
        assertEquals(Atom.of("direct"), pidPacket[1]);
        assertNull("fake peer failed: " + peerFailure, peerFailure);
    }

    @Test
    public void anUnknownNodeReturnsPang() throws Throwable {
        Atom result = Erlang.apply(Atom.of("net_adm"), Atom.of("ping"),
            List.of(Atom.of("nobody@127.0.0.1"))).toAtom();

        assertEquals(Atom.of("pang"), result);
        assertFalse(Distribution.isConnected(Atom.of("nobody@127.0.0.1")));
    }

    @Test
    public void aWrongCookieReturnsPang() throws Throwable {
        peerCookie = "not-the-cookie";

        Atom result = Erlang.apply(Atom.of("net_adm"), Atom.of("ping"), List.of(PEER))
            .toAtom();

        assertEquals(Atom.of("pang"), result);
        assertFalse(Distribution.isConnected(PEER));
        assertEquals(List.nil, Erlang.nodes());
    }

    private static Term awaitMessage(Process process) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 3000;
        while (!process.hasMessage() && System.currentTimeMillis() < deadline) {
            Thread.sleep(10);
        }
        assertTrue("no message was delivered", process.hasMessage());
        Term message = process.nextMessage();
        process.removeMessage();
        return message;
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

    private void peerLoop() {
        try (Socket socket = peerSocket.accept()) {
            socket.setSoTimeout(5000);
            DataInputStream in = new DataInputStream(new BufferedInputStream(socket.getInputStream()));
            DataOutputStream out = new DataOutputStream(new BufferedOutputStream(socket.getOutputStream()));

            byte[] namePacket = readPacket2(in);
            DataInputStream name = new DataInputStream(new ByteArrayInputStream(namePacket));
            assertEquals(78, name.readUnsignedByte()); // 'N'
            clientFlags = name.readLong();
            clientCreation = name.readInt();
            clientName = readShortString(name);

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

            if (!MessageDigest.isEqual(digest(challenge, peerCookie), clientDigest)) {
                // The peer refuses the connection, as OTP does on a bad cookie.
                return;
            }

            byte[] ack = new byte[17];
            ack[0] = 'a';
            System.arraycopy(digest(clientChallenge, peerCookie), 0, ack, 1, 16);
            writePacket2(out, ack);

            // Deliver one REG_SEND and one SEND to the local node.
            PID from = new PID(PEER, 5, 0, 0);
            PID toPid = new PID(Atom.of(clientName), 123, 0, clientCreation);
            writeMessage(out, Tuple.of(Integer.of(6), from, List.nil, Atom.of("sink")),
                Tuple.of(Atom.of("incoming"), Integer.of(1)));
            writeMessage(out, Tuple.of(Integer.of(2), List.nil, toPid),
                Atom.of("hello-from-peer"));

            readMessages(in);
        } catch (EOFException e) {
            // The client closed the connection; that is normal in the ping tests.
        } catch (Throwable e) {
            peerFailure = e;
        }
    }

    private void readMessages(DataInputStream in) throws Throwable {
        while (true) {
            int length;
            try {
                length = in.readInt();
            } catch (EOFException e) {
                return;
            }
            if (length == 0) {
                continue;
            }
            byte[] payload = new byte[length];
            in.readFully(payload);
            DataInputStream terms = new DataInputStream(
                new ByteArrayInputStream(payload, 1, payload.length - 1));
            Term control = new ExternalTermReader(terms).read();
            Term message = new ExternalTermReader(terms).read();
            received.add(new Term[] { control, message });
        }
    }

    private static void writeMessage(DataOutputStream out, Term control, Term message)
        throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        DataOutputStream packet = new DataOutputStream(buffer);
        packet.writeByte(112);
        packet.write(org.jerlang.erts.etf.ExternalTermWriter.encode(control).toByteArray());
        packet.write(org.jerlang.erts.etf.ExternalTermWriter.encode(message).toByteArray());
        out.writeInt(buffer.size());
        out.write(buffer.toByteArray());
        out.flush();
    }

    private static byte[] digest(int challenge, String cookie) throws Exception {
        MessageDigest md5 = MessageDigest.getInstance("MD5");
        return md5.digest((cookie + java.lang.Integer.toUnsignedString(challenge))
            .getBytes(StandardCharsets.UTF_8));
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

    private static void writeShortString(DataOutputStream out, String string) throws IOException {
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

    private static void startThread(Runnable runnable, String name) {
        Thread thread = new Thread(runnable, name);
        thread.setDaemon(true);
        thread.start();
    }

}
