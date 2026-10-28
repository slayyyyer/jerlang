package org.jerlang.erts.dist;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.concurrent.ThreadLocalRandom;

import org.jerlang.erts.etf.ExternalTermReader;
import org.jerlang.erts.etf.ExternalTermWriter;
import org.jerlang.type.Atom;
import org.jerlang.type.Integer;
import org.jerlang.type.List;
import org.jerlang.type.PID;
import org.jerlang.type.Term;
import org.jerlang.type.Tuple;
import org.jerlang.vm.VirtualMachine;

/**
 * One TCP connection to another Erlang node, after a successful
 * distribution handshake.
 *
 * Messages use the pre-distribution-header format:
 *
 *   length (4 bytes) | 112 | control term | message term
 *
 * The distribution header with its atom cache is not negotiated, so both
 * sides encode the control and message terms with their own version byte.
 */
public final class DistConnection {

    /** Type tag for distribution messages without a distribution header. */
    private static final int TYPE_TAG = 112;

    private static final Atom NET_KERNEL = Atom.of("net_kernel");
    private static final Atom GEN_CALL = Atom.of("$gen_call");
    private static final Atom IS_AUTH = Atom.of("is_auth");
    private static final Atom YES = Atom.of("yes");

    private static final int MAX_PACKET = 64 * 1024 * 1024;
    private static final int CONNECT_TIMEOUT = 5000;
    private static final int TICK_INTERVAL = 30000;

    private final Atom peer;
    private final Socket socket;
    private final DataInputStream in;
    private final DataOutputStream out;
    private final Thread reader;
    private final Thread ticker;
    private volatile boolean open = true;

    private DistConnection(Atom peer, Socket socket, DataInputStream in, DataOutputStream out) {
        this.peer = peer;
        this.socket = socket;
        this.in = in;
        this.out = out;
        this.reader = new Thread(this::readLoop, "jerlang-dist-" + peer);
        this.reader.setDaemon(true);
        this.ticker = new Thread(this::tickLoop, "jerlang-dist-tick-" + peer);
        this.ticker.setDaemon(true);
        this.reader.start();
        this.ticker.start();
    }

    /**
     * Wrap a socket whose server-side handshake has already completed.
     */
    static DistConnection accepted(Atom peer, Socket socket, DataInputStream in, DataOutputStream out) {
        DistConnection connection = new DistConnection(peer, socket, in, out);
        Distribution.add(connection);
        return connection;
    }

    /**
     * Connect to a peer: find its port in EPMD, then run the handshake.
     */
    public static DistConnection open(Atom peer) throws IOException {
        Atom local = VirtualMachine.instance().node();
        if (!DistNodeName.isValid(local)) {
            throw new IOException("Distribution requires a named node, not " + local);
        }
        if (!DistNodeName.isValid(peer)) {
            throw new IOException("Not a node name: " + peer);
        }

        String host = DistNodeName.host(peer);
        String name = DistNodeName.name(peer);
        int port = EpmdClient.port(host, name);

        Socket socket = new Socket();
        try {
            socket.connect(new InetSocketAddress(host, port), CONNECT_TIMEOUT);
            socket.setTcpNoDelay(true);
            DataInputStream in = new DataInputStream(new BufferedInputStream(socket.getInputStream()));
            DataOutputStream out = new DataOutputStream(new BufferedOutputStream(socket.getOutputStream()));
            handshake(local, peer, in, out);
            socket.setSoTimeout(0);
            return new DistConnection(peer, socket, in, out);
        } catch (IOException | RuntimeException e) {
            try {
                socket.close();
            } catch (IOException ignored) {
                // The original failure is the interesting one.
            }
            throw e;
        }
    }

    /**
     * Client side of the version 6 distribution handshake.
     *
     * See:
     * http://www.erlang.org/doc/apps/erts/erl_dist_protocol.html#distribution-handshake
     */
    private static void handshake(Atom local, Atom peer, DataInputStream in, DataOutputStream out)
        throws IOException {
        String cookie = DistCookie.getCookie();
        int creation = VirtualMachine.instance().nodeCreation();
        int ourChallenge = ThreadLocalRandom.current().nextInt();

        // send_name: 'N', 64 bit flags, 32 bit creation, name.
        debug("sending name " + local + " to " + peer);
        writePacket2(out, sendName(local, creation));

        byte[] status = readPacket2(in);
        debug("status " + new String(status, StandardCharsets.UTF_8));
        if (status.length < 1 || status[0] != 's') {
            throw new IOException("Expected a handshake status, got tag "
                + (status.length > 0 ? status[0] : -1));
        }
        String statusText = new String(status, 1, status.length - 1, StandardCharsets.UTF_8);
        if (!statusText.equals("ok") && !statusText.equals("ok_simultaneous")) {
            throw new IOException("Peer refused the connection: " + statusText);
        }

        byte[] challengePacket = readPacket2(in);
        DataInputStream challenge = new DataInputStream(new ByteArrayInputStream(challengePacket));
        int tag = challenge.readUnsignedByte();
        if (tag != 'N') {
            throw new IOException("Expected a version 6 challenge, got tag " + tag);
        }
        long peerFlags = challenge.readLong();
        int peerChallenge = challenge.readInt();
        challenge.readInt(); // peer creation
        readShortString(challenge); // peer node name

        if ((peerFlags & DistFlags.HANDSHAKE_23) == 0) {
            throw new IOException("Peer does not support the version 6 handshake");
        }
        debug("peer flags " + Long.toHexString(peerFlags)
            + " challenge " + java.lang.Integer.toUnsignedString(peerChallenge));

        ByteArrayOutputStream replyBuffer = new ByteArrayOutputStream();
        DataOutputStream reply = new DataOutputStream(replyBuffer);
        reply.writeByte('r');
        reply.writeInt(ourChallenge);
        reply.write(digest(peerChallenge, cookie));
        writePacket2(out, replyBuffer.toByteArray());
        debug("sent challenge reply");

        byte[] ack = readPacket2(in);
        if (ack.length != 17 || ack[0] != 'a') {
            throw new IOException("Expected a challenge acknowledgement");
        }
        if (!MessageDigest.isEqual(digest(ourChallenge, cookie),
            java.util.Arrays.copyOfRange(ack, 1, 17))) {
            throw new IOException("The peer did not prove knowledge of the cookie");
        }

        debug("handshake with " + peer + " completed");
    }

    private static byte[] sendName(Atom local, int creation) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        DataOutputStream packet = new DataOutputStream(buffer);
        packet.writeByte('N');
        packet.writeLong(DistFlags.OURS);
        packet.writeInt(creation);
        writeShortString(packet, local.toString());
        return buffer.toByteArray();
    }

    private static byte[] digest(int challenge, String cookie) throws IOException {
        try {
            MessageDigest md5 = MessageDigest.getInstance("MD5");
            String text = cookie + java.lang.Integer.toUnsignedString(challenge);
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
        if (bytes.length > 0xFFFF) {
            throw new IOException("Node name is too long");
        }
        out.writeShort(bytes.length);
        out.write(bytes);
    }

    private static String readShortString(DataInputStream in) throws IOException {
        int length = in.readUnsignedShort();
        byte[] bytes = new byte[length];
        in.readFully(bytes);
        return new String(bytes, StandardCharsets.UTF_8);
    }

    public Atom peer() {
        return peer;
    }

    public boolean isOpen() {
        return open;
    }

    /**
     * Send a message to a process identifier on the peer node.
     */
    public void sendPid(PID to, Term message) throws IOException {
        sendControl(Tuple.of(Integer.of(2), List.nil, to), message);
    }

    /**
     * Send a message to a registered name on the peer node.
     */
    public void sendName(Atom name, PID from, Term message) throws IOException {
        sendControl(Tuple.of(Integer.of(6), from, List.nil, name), message);
    }

    private void sendControl(Term control, Term message) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        DataOutputStream packet = new DataOutputStream(buffer);
        packet.writeByte(TYPE_TAG);
        packet.write(ExternalTermWriter.encode(control).toByteArray());
        if (message != null) {
            packet.write(ExternalTermWriter.encode(message).toByteArray());
        }
        byte[] payload = buffer.toByteArray();

        synchronized (out) {
            if (!open) {
                throw new IOException("Connection to " + peer + " is closed");
            }
            out.writeInt(payload.length);
            out.write(payload);
            out.flush();
        }
    }

    private void readLoop() {
        try {
            while (open) {
                int length = in.readInt();
                if (length == 0) {
                    // A tick: no payload.
                    continue;
                }
                if (length < 0 || length > MAX_PACKET) {
                    throw new IOException("Invalid distribution packet length " + length);
                }
                byte[] payload = new byte[length];
                in.readFully(payload);
                if (payload.length == 0 || (payload[0] & 0xFF) != TYPE_TAG) {
                    throw new IOException("Unexpected distribution packet type "
                        + (payload.length > 0 ? (payload[0] & 0xFF) : -1)
                        + " length " + payload.length);
                }
                DataInputStream terms = new DataInputStream(
                    new ByteArrayInputStream(payload, 1, payload.length - 1));
                Term control;
                try {
                    control = new ExternalTermReader(terms).read();
                } catch (RuntimeException e) {
                    throw new IOException("Can not decode control " + e + " in packet of "
                        + payload.length + " bytes starting with " + hex(payload, 12), e);
                }
                try {
                    handle(control, terms);
                } catch (RuntimeException e) {
                    // A message we can not decode (OTP uses a few terms this
                    // node does not implement yet, for example an improper
                    // list in a net_kernel call) must not take the whole
                    // connection down.
                    debug("dropping undecodable message after " + control + ": " + e);
                }
            }
        } catch (EOFException e) {
            debug("connection to " + peer + " closed by peer");
        } catch (Throwable e) {
            if (open) {
                debug("connection to " + peer + " failed: " + e);
            }
        } finally {
            close();
        }
    }

    private void handle(Term control, DataInputStream terms) throws Throwable {
        if (!(control instanceof Tuple)) {
            return;
        }
        Tuple tuple = control.toTuple();
        if (tuple.arity() < 1) {
            return;
        }
        int operation = tuple.element(1).toInteger().toInt();
        switch (operation) {
        case 2: // SEND: {2, Unused, ToPid} followed by the message
            if (tuple.arity() >= 3) {
                PID to = tuple.element(3).toPID();
                if (Distribution.hasLocal(to)) {
                    Distribution.deliverLocal(to, new ExternalTermReader(terms).read());
                } else {
                    debug("dropping message to unknown pid " + to);
                }
            }
            break;
        case 6: // REG_SEND: {6, FromPid, Unused, ToName} followed by the message
            if (tuple.arity() >= 4) {
                Atom to = tuple.element(4).toAtom();
                if (to.equals(NET_KERNEL)) {
                    answerNetKernel(new ExternalTermReader(terms).read());
                } else if (Distribution.hasLocal(to)) {
                    Distribution.deliverLocal(to, new ExternalTermReader(terms).read());
                } else {
                    debug("dropping message to unknown name " + to);
                }
            }
            break;
        default:
            // EXIT, NODE_LINK and other signals do not carry a message here.
            break;
        }
    }

    /**
     * Node-to-node authentication: a connecting OTP node asks its local
     * net_kernel whether this node is authorized. The distribution
     * handshake already proved the cookie, so the answer is yes.
     */
    private void answerNetKernel(Term message) throws IOException {
        if (!(message instanceof Tuple)) {
            return;
        }
        Tuple call = (Tuple) message;
        if (call.arity() != 3 || !GEN_CALL.equals(call.element(1))
            || !(call.element(2) instanceof Tuple)) {
            return;
        }
        Tuple from = (Tuple) call.element(2);
        if (from.arity() != 2 || !(from.element(1) instanceof PID)) {
            return;
        }
        Term request = call.element(3);
        if (!(request instanceof Tuple) || ((Tuple) request).arity() != 2
            || !IS_AUTH.equals(((Tuple) request).element(1))) {
            return;
        }
        sendPid(from.element(1).toPID(), Tuple.of(from.element(2), YES));
    }

    private void tickLoop() {
        while (open) {
            try {
                Thread.sleep(TICK_INTERVAL);
                synchronized (out) {
                    if (open) {
                        out.writeInt(0);
                        out.flush();
                    }
                }
            } catch (InterruptedException e) {
                return;
            } catch (IOException e) {
                close();
                return;
            }
        }
    }

    public void close() {
        if (!open) {
            return;
        }
        open = false;
        try {
            socket.close();
        } catch (IOException ignored) {
            // Closing is best effort.
        }
        Distribution.remove(this);
        debug("closed connection to " + peer);
    }

    private static String hex(byte[] bytes, int count) {
        StringBuilder text = new StringBuilder();
        for (int index = 0; index < Math.min(count, bytes.length); index++) {
            text.append(String.format("%02x", bytes[index] & 0xFF));
        }
        return text.toString();
    }

    private static void debug(String message) {
        if (Boolean.getBoolean("jerlang.dist.debug")) {
            System.err.println("jerlang dist: " + message);
        }
    }

}
