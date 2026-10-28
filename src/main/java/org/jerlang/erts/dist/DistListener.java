package org.jerlang.erts.dist;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
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
import java.security.NoSuchAlgorithmException;
import java.util.concurrent.ThreadLocalRandom;

import org.jerlang.type.Atom;
import org.jerlang.vm.VirtualMachine;

/**
 * Accepts distribution connections from other nodes and registers this
 * node with the Erlang Port Mapper Daemon, so that nodes can find it.
 *
 * The listener is started by VirtualMachine.start() when the node has a
 * name of the form name@host. Without EPMD registration the listener can
 * still be reached directly on its port, but net_adm:ping by name cannot
 * find it.
 */
public final class DistListener {

    private static DistListener current;

    private final Atom node;
    private final ServerSocket server;
    private final Thread acceptor;
    private Socket epmdRegistration;
    private volatile boolean running = true;

    private DistListener(Atom node) throws IOException {
        this.node = node;
        InetAddress address = InetAddress.getByName("0.0.0.0");
        server = new ServerSocket();
        server.bind(new InetSocketAddress(address,
            Integer.getInteger("jerlang.dist.port", 0)), 50);
        acceptor = new Thread(this::acceptLoop, "jerlang-dist-listener");
        acceptor.setDaemon(true);
    }

    /**
     * Start the listener for the current node, if it has a name. Repeated
     * calls are ignored while the listener is running.
     */
    public static synchronized void start() {
        Atom node = VirtualMachine.instance().node();
        if (!DistNodeName.isValid(node)) {
            return;
        }
        if (current != null && current.running) {
            return;
        }
        try {
            DistListener listener = new DistListener(node);
            listener.registerWithEpmd();
            listener.acceptor.start();
            current = listener;
            debug("listening on " + listener.port() + " as " + node);
        } catch (IOException e) {
            debug("can not start distribution listener: " + e);
        }
    }

    public static synchronized void stop() {
        DistListener listener = current;
        current = null;
        if (listener != null) {
            listener.shutdown();
        }
    }

    public static synchronized int port() {
        return current == null ? -1 : current.server.getLocalPort();
    }

    private void registerWithEpmd() {
        Atom node = this.node;
        String name = DistNodeName.name(node);
        String host = DistNodeName.host(node);
        try {
            epmdRegistration = EpmdClient.register(host, name, server.getLocalPort());
            debug("registered " + name + " with EPMD on port " + server.getLocalPort());
        } catch (IOException e) {
            // The listener still works for direct connections. OTP can not
            // find it by name without EPMD, which is common in tests.
            debug("could not register with EPMD: " + e);
        }
    }

    private void acceptLoop() {
        while (running) {
            try {
                Socket socket = server.accept();
                socket.setTcpNoDelay(true);
                try {
                    accept(socket);
                } catch (Throwable e) {
                    debug("incoming connection failed: " + e);
                    try {
                        socket.close();
                    } catch (IOException ignored) {
                        // Already failing.
                    }
                }
            } catch (IOException e) {
                if (running) {
                    debug("accept failed: " + e);
                }
            }
        }
    }

    /**
     * Server side of the version 6 distribution handshake.
     *
     * The initiator sends its name, we send a status and our challenge,
     * it proves knowledge of the cookie, and we prove ours in the
     * acknowledgement.
     */
    private void accept(Socket socket) throws IOException {
        DataInputStream in = new DataInputStream(new BufferedInputStream(socket.getInputStream()));
        DataOutputStream out = new DataOutputStream(new BufferedOutputStream(socket.getOutputStream()));

        byte[] namePacket = readPacket2(in);
        DataInputStream name = new DataInputStream(new ByteArrayInputStream(namePacket));
        int tag = name.readUnsignedByte();
        if (tag != 'N') {
            // Old version 5 names are only sent by nodes that did not ask
            // EPMD for the capabilities of this node. Refuse politely.
            writePacket2(out, "snok".getBytes(StandardCharsets.UTF_8));
            socket.close();
            return;
        }
        name.readLong(); // peer flags
        name.readInt(); // peer creation
        Atom peer = Atom.of(readShortString(name));
        if (!DistNodeName.isValid(peer) || peer.equals(node)) {
            writePacket2(out, "snok".getBytes(StandardCharsets.UTF_8));
            socket.close();
            return;
        }
        debug("incoming connection from " + peer);

        writePacket2(out, "sok".getBytes(StandardCharsets.UTF_8));

        int ourChallenge = ThreadLocalRandom.current().nextInt();
        ByteArrayOutputStream challengeBuffer = new ByteArrayOutputStream();
        DataOutputStream challenge = new DataOutputStream(challengeBuffer);
        challenge.writeByte('N');
        challenge.writeLong(DistFlags.OURS);
        challenge.writeInt(ourChallenge);
        challenge.writeInt(VirtualMachine.instance().nodeCreation());
        writeShortString(challenge, node.toString());
        writePacket2(out, challengeBuffer.toByteArray());

        byte[] replyPacket = readPacket2(in);
        DataInputStream reply = new DataInputStream(new ByteArrayInputStream(replyPacket));
        if (reply.readUnsignedByte() != 'r') {
            socket.close();
            return;
        }
        int peerChallenge = reply.readInt();
        byte[] peerDigest = new byte[16];
        reply.readFully(peerDigest);
        if (!MessageDigest.isEqual(digest(ourChallenge), peerDigest)) {
            debug("incoming connection from " + peer + " used a wrong cookie");
            socket.close();
            return;
        }

        ByteArrayOutputStream ackBuffer = new ByteArrayOutputStream();
        DataOutputStream ack = new DataOutputStream(ackBuffer);
        ack.writeByte('a');
        ack.write(digest(peerChallenge));
        writePacket2(out, ackBuffer.toByteArray());

        socket.setSoTimeout(0);
        DistConnection.accepted(peer, socket, in, out);
    }

    private static byte[] digest(int challenge) throws IOException {
        try {
            MessageDigest md5 = MessageDigest.getInstance("MD5");
            String text = DistCookie.getCookie() + java.lang.Integer.toUnsignedString(challenge);
            return md5.digest(text.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException e) {
            throw new IOException("MD5 is not available", e);
        }
    }

    private static void writePacket2(DataOutputStream out, byte[] payload) throws IOException {
        if (payload.length > 0xFFFF) {
            throw new IOException("Handshake message is too large");
        }
        out.writeShort(payload.length);
        out.write(payload);
        out.flush();
    }

    private static byte[] readPacket2(DataInputStream in) throws IOException {
        int length = in.readUnsignedShort();
        byte[] payload = new byte[length];
        in.readFully(payload);
        return payload;
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

    private void shutdown() {
        running = false;
        try {
            server.close();
        } catch (IOException ignored) {
            // Closing is best effort.
        }
        if (epmdRegistration != null) {
            try {
                epmdRegistration.close();
            } catch (IOException ignored) {
                // Closing is best effort.
            }
        }
        acceptor.interrupt();
    }

    private static void debug(String message) {
        if (Boolean.getBoolean("jerlang.dist.debug")) {
            System.err.println("jerlang dist: " + message);
        }
    }

}
