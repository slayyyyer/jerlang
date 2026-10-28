package org.jerlang.erts.dist;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.io.BufferedInputStream;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.jerlang.Process;
import org.jerlang.ProcessRegistry;
import org.jerlang.erts.etf.ExternalTermWriter;
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
 * Exercises the accepting side of distribution without a running OTP
 * installation: a fake EPMD receives the ALIVE2_REQ registration and a
 * fake OTP node performs the version 6 handshake and sends messages.
 */
public class DistListenerTest {

    private static final Atom LOCAL = Atom.of("jerlang@127.0.0.1");
    private static final Atom CLIENT = Atom.of("client@127.0.0.1");
    private static final String COOKIE = "listener-test-cookie";

    private VirtualMachine vm;
    private Atom previousNode;
    private int previousCreation;
    private ServerSocket epmd;
    private final java.util.List<Socket> registrations = new ArrayList<>();
    private final BlockingQueue<int[]> registrationsSeen = new LinkedBlockingQueue<>();
    private final AtomicReference<Throwable> epmdFailure = new AtomicReference<>();

    private Process sink;

    @Before
    public void setUp() throws Exception {
        vm = VirtualMachine.instance();
        previousNode = vm.node();
        previousCreation = vm.nodeCreation();
        vm.setNode(LOCAL, 1);
        DistCookie.setCookie(COOKIE);

        epmd = new ServerSocket(0, 4, InetAddress.getByName("127.0.0.1"));
        System.setProperty("jerlang.epmd.port", String.valueOf(epmd.getLocalPort()));
        Thread epmdThread = new Thread(this::epmdLoop, "fake-epmd");
        epmdThread.setDaemon(true);
        epmdThread.start();

        vm.start();

        sink = new Process(new PID(123));
        ProcessRegistry.register(sink);
        ProcessRegistry.register(Atom.of("sink"), sink.id());
    }

    @After
    public void tearDown() throws Exception {
        Distribution.shutdown();
        vm.shutdown();
        epmd.close();
        synchronized (registrations) {
            for (Socket socket : registrations) {
                socket.close();
            }
            registrations.clear();
        }
        System.clearProperty("jerlang.epmd.port");
        DistCookie.reset();
        vm.setNode(previousNode, previousCreation);
    }

    @Test
    public void listensAndAcceptsAnOtpStyleHandshake() throws Throwable {
        int[] registration = registrationsSeen.poll(3, TimeUnit.SECONDS);
        assertNotNull("the listener did not register with EPMD", registration);
        assertEquals(DistListener.port(), registration[0]);
        assertTrue("EPMD registered the wrong name", registration[1] > 0);

        try (Socket client = new Socket()) {
            client.connect(new InetSocketAddress("127.0.0.1", DistListener.port()), 3000);
            DataInputStream in = new DataInputStream(new BufferedInputStream(client.getInputStream()));
            DataOutputStream out = new DataOutputStream(client.getOutputStream());
            handshake(in, out);

            writeMessage(out, Tuple.of(Integer.of(6), new PID(CLIENT, 5, 0, 0), List.nil, Atom.of("sink")),
                Tuple.of(Atom.of("incoming"), Integer.of(1)));
            writeMessage(out, Tuple.of(Integer.of(2), List.nil, new PID(LOCAL, 123, 0, 1)),
                Atom.of("hello-from-client"));

            assertEquals(Tuple.of(Atom.of("incoming"), Integer.of(1)), awaitMessage(sink));
            assertEquals(Atom.of("hello-from-client"), awaitMessage(sink));

            // Check while the client is still connected: closing the socket
            // makes the reader thread drop the connection asynchronously.
            assertTrue(Distribution.isConnected(CLIENT));
            assertEquals(List.of(CLIENT), Distribution.nodes());
            assertNull("fake EPMD failed: " + epmdFailure.get(), epmdFailure.get());
        }
    }

    private void handshake(DataInputStream in, DataOutputStream out) throws Exception {
        ByteArrayOutputStream nameBuffer = new ByteArrayOutputStream();
        DataOutputStream name = new DataOutputStream(nameBuffer);
        name.writeByte('N');
        name.writeLong(DistFlags.OURS);
        name.writeInt(1);
        writeShortString(name, CLIENT.toString());
        writePacket2(out, nameBuffer.toByteArray());

        byte[] status = readPacket2(in);
        assertEquals("sok", new String(status, StandardCharsets.UTF_8));

        byte[] challengePacket = readPacket2(in);
        DataInputStream challenge = new DataInputStream(new ByteArrayInputStream(challengePacket));
        assertEquals('N', challenge.readUnsignedByte());
        challenge.readLong(); // flags
        int peerChallenge = challenge.readInt();
        challenge.readInt(); // creation
        assertEquals(LOCAL.toString(), readShortString(challenge));

        int ourChallenge = 0x10203040;
        ByteArrayOutputStream replyBuffer = new ByteArrayOutputStream();
        DataOutputStream reply = new DataOutputStream(replyBuffer);
        reply.writeByte('r');
        reply.writeInt(ourChallenge);
        reply.write(digest(peerChallenge));
        writePacket2(out, replyBuffer.toByteArray());

        byte[] ack = readPacket2(in);
        assertEquals('a', ack[0]);
        assertTrue(MessageDigest.isEqual(digest(ourChallenge),
            java.util.Arrays.copyOfRange(ack, 1, 17)));
    }

    private static byte[] digest(int challenge) throws Exception {
        MessageDigest md5 = MessageDigest.getInstance("MD5");
        return md5.digest((COOKIE + java.lang.Integer.toUnsignedString(challenge))
            .getBytes(StandardCharsets.UTF_8));
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
                Socket socket = epmd.accept();
                DataInputStream in = new DataInputStream(socket.getInputStream());
                int length = in.readUnsignedShort();
                byte[] request = new byte[length];
                in.readFully(request);
                if (request[0] != 'x') {
                    socket.close();
                    continue;
                }

                DataInputStream registration = new DataInputStream(
                    new ByteArrayInputStream(request, 1, request.length - 1));
                int port = registration.readUnsignedShort();
                registration.readUnsignedByte(); // node type
                registration.readUnsignedByte(); // protocol
                registration.readUnsignedShort(); // highest version
                registration.readUnsignedShort(); // lowest version
                int nameLength = registration.readUnsignedShort();
                byte[] name = new byte[nameLength];
                registration.readFully(name);
                String nodeName = new String(name, StandardCharsets.UTF_8);
                registrationsSeen.add(new int[] { port, nameLength });

                DataOutputStream response = new DataOutputStream(socket.getOutputStream());
                response.writeByte('y'); // ALIVE2_RESP with a 16 bit creation
                response.writeByte(0); // ok
                response.writeShort(0); // creation
                response.flush();
                synchronized (registrations) {
                    registrations.add(socket);
                }
                assertTrue(nodeName.length() > 0);
            }
        } catch (IOException e) {
            if (!epmd.isClosed()) {
                epmdFailure.set(e);
            }
        }
    }

    private static void writeMessage(DataOutputStream out, Term control, Term message)
        throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        DataOutputStream packet = new DataOutputStream(buffer);
        packet.writeByte(112);
        packet.write(ExternalTermWriter.encode(control).toByteArray());
        packet.write(ExternalTermWriter.encode(message).toByteArray());
        out.writeInt(buffer.size());
        out.write(buffer.toByteArray());
        out.flush();
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

}
