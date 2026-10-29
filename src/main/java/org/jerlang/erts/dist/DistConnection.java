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
import java.util.concurrent.atomic.AtomicInteger;

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
    private static final Atom NOPROC = Atom.of("noproc");
    private static final Atom GEN_CALL = Atom.of("$gen_call");
    private static final Atom IS_AUTH = Atom.of("is_auth");
    private static final Atom YES = Atom.of("yes");

    private static final int MAX_PACKET = 64 * 1024 * 1024;
    private static final int CONNECT_TIMEOUT = 5000;
    private static final int DEFAULT_TICK_TIME_SECONDS = 60;

    // OTP's connection identifiers are 32 bit integers. They only need to
    // be unique among the connections that a monitor_nodes subscriber can
    // observe, so a process-wide counter is enough.
    private static final AtomicInteger NEXT_CONNECTION_ID = new AtomicInteger(1);

    // Control message tags, see the distribution protocol.
    private static final int LINK = 1;
    private static final int SEND = 2;
    private static final int EXIT = 3;
    private static final int UNLINK = 4;
    private static final int REG_SEND = 6;
    private static final int EXIT2 = 8;
    private static final int MONITOR_P = 19;
    private static final int DEMONITOR_P = 20;
    private static final int MONITOR_P_EXIT = 21;
    private static final int SPAWN_REQUEST = 29;
    private static final int SPAWN_REPLY = 31;
    private static final int UNLINK_ID = 35;
    private static final int UNLINK_ID_ACK = 36;

    private final Atom peer;
    private final long peerFlags;
    private final boolean hidden;
    private final int connectionId;
    private final Socket socket;
    private final DataInputStream in;
    private final DataOutputStream out;
    private final Thread reader;
    private final Thread ticker;
    private volatile boolean open = true;
    private volatile long lastReceived = System.currentTimeMillis();
    private volatile Term closeReason = Atom.of("connection_closed");

    private DistConnection(Atom peer, long peerFlags, Socket socket, DataInputStream in,
        DataOutputStream out) {
        this.peer = peer;
        this.peerFlags = peerFlags;
        // A hidden node does not set DFLAG_PUBLISHED, and a hidden local
        // node makes every connection hidden, as OTP's net_kernel does.
        this.hidden = VirtualMachine.instance().isHidden()
            || (peerFlags & DistFlags.PUBLISHED) == 0;
        this.connectionId = NEXT_CONNECTION_ID.getAndIncrement();
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
    static DistConnection accepted(Atom peer, long peerFlags, Socket socket, DataInputStream in,
        DataOutputStream out) {
        DistConnection connection = new DistConnection(peer, peerFlags, socket, in, out);
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
            long peerFlags = handshake(local, peer, in, out);
            socket.setSoTimeout(0);
            return new DistConnection(peer, peerFlags, socket, in, out);
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
    private static long handshake(Atom local, Atom peer, DataInputStream in, DataOutputStream out)
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
        return peerFlags;
    }

    private static byte[] sendName(Atom local, int creation) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        DataOutputStream packet = new DataOutputStream(buffer);
        packet.writeByte('N');
        packet.writeLong(DistFlags.ours(VirtualMachine.instance().isHidden()));
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

    /** Whether this is a hidden connection (not in erlang:nodes/0). */
    public boolean isHidden() {
        return hidden;
    }

    /** The identifier net_kernel:monitor_nodes/2 reports. */
    public int connectionId() {
        return connectionId;
    }

    /** Why the connection closed, for a nodedown_reason. */
    public Term closeReason() {
        return closeReason;
    }

    /**
     * Whether the peer announced that it handles SPAWN_REQUEST and
     * SPAWN_REPLY.
     */
    public boolean supportsSpawn() {
        return (peerFlags & DistFlags.SPAWN) != 0;
    }

    /**
     * Send a message to a process identifier on the peer node.
     */
    public void sendPid(PID to, Term message) throws IOException {
        sendControl(Tuple.of(Integer.of(SEND), List.nil, to), message);
    }

    /**
     * Send a message to a registered name on the peer node.
     */
    public void sendName(Atom name, PID from, Term message) throws IOException {
        sendControl(Tuple.of(Integer.of(REG_SEND), from, List.nil, name), message);
    }

    /**
     * LINK: ask the peer to link the two processes. The peer answers with
     * an EXIT signal if its side is already gone.
     */
    public void sendLink(PID from, PID to) throws IOException {
        sendControl(Tuple.of(Integer.of(LINK), from, to), null);
    }

    /**
     * UNLINK_ID: break a link that both sides agree exists. The peer has to
     * answer with an UNLINK_ID_ACK before it sends anything else to us.
     */
    public void sendUnlink(PID from, PID to, long id) throws IOException {
        sendControl(Tuple.of(Integer.of(UNLINK_ID), new Integer(id), from, to), null);
    }

    /**
     * The identifier is echoed back exactly as received: it is an unsigned
     * 64 bit integer, which does not necessarily fit in a Java long.
     */
    public void sendUnlinkAck(PID from, PID to, Integer id) throws IOException {
        sendControl(Tuple.of(Integer.of(UNLINK_ID_ACK), id, from, to), null);
    }

    /**
     * EXIT (a link was broken) or EXIT2 (an explicit erlang:exit/2).
     */
    public void sendExit(PID from, PID to, Term reason, boolean explicit) throws IOException {
        sendControl(Tuple.of(Integer.of(explicit ? EXIT2 : EXIT), from, to, reason), null);
    }

    /**
     * MONITOR_P: `target` is a PID, or a registered name when monitoring
     * `{Name, Node}`.
     */
    public void sendMonitor(PID from, Term target, Reference reference) throws IOException {
        sendControl(Tuple.of(Integer.of(MONITOR_P), from, target, reference), null);
    }

    public void sendDemonitor(PID from, Term target, Reference reference) throws IOException {
        sendControl(Tuple.of(Integer.of(DEMONITOR_P), from, target, reference), null);
    }

    /**
     * MONITOR_P_EXIT: `from` is the monitored PID, or the registered name
     * that was monitored.
     */
    public void sendMonitorExit(Term from, PID to, Reference reference, Term reason)
        throws IOException {
        sendControl(Tuple.of(Integer.of(MONITOR_P_EXIT), from, to, reference, reason), null);
    }

    /**
     * SPAWN_REQUEST: the argument list follows the control term. The group
     * leader is sent as the requesting process, because JErlang does not
     * implement process groups yet.
     */
    public void sendSpawnRequest(Reference requestId, PID from, PID groupLeader, Atom module,
        Atom function, List args, List options) throws IOException {
        Tuple mfa = Tuple.of(module, function, Integer.of(args.length()));
        sendControl(Tuple.of(Integer.of(SPAWN_REQUEST), requestId, from, groupLeader, mfa,
            options), args);
    }

    /**
     * SPAWN_REPLY: `result` is the PID of the new process, or an atom that
     * names the failure. `flags` bit 1 marks an established link, bit 2 an
     * established monitor.
     */
    public void sendSpawnReply(Reference requestId, PID to, int flags, Term result)
        throws IOException {
        sendControl(Tuple.of(Integer.of(SPAWN_REPLY), requestId, to, Integer.of(flags), result),
            null);
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
                // Any packet proves that the peer is alive, including a
                // zero length net tick.
                lastReceived = System.currentTimeMillis();
                if (length == 0) {
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
        case SEND: // {2, Unused, ToPid} followed by the message
            if (tuple.arity() >= 3) {
                PID to = tuple.element(3).toPID();
                if (Distribution.hasLocal(to)) {
                    Distribution.deliverLocal(to, new ExternalTermReader(terms).read());
                } else {
                    debug("dropping message to unknown pid " + to);
                }
            }
            break;
        case REG_SEND: // {6, FromPid, Unused, ToName} followed by the message
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
        case LINK: // {1, FromPid, ToPid}
            if (tuple.arity() >= 3) {
                PID from = tuple.element(2).toPID();
                PID to = tuple.element(3).toPID();
                if (!Distribution.linkReceived(from, to)) {
                    // The answer has to go back over this connection, not
                    // through a new one to the node of the dead PID.
                    sendExit(to, from, NOPROC, false);
                }
            }
            break;
        case EXIT: // {3, FromPid, ToPid, Reason}
            if (tuple.arity() >= 4) {
                Distribution.exitReceived(tuple.element(2).toPID(), tuple.element(3).toPID(),
                    tuple.element(4), false);
            }
            break;
        case UNLINK: // legacy {4, FromPid, ToPid}, replaced by UNLINK_ID
            if (tuple.arity() >= 3) {
                Distribution.unlinkReceived(tuple.element(2).toPID(), tuple.element(3).toPID());
            }
            break;
        case EXIT2: // {8, FromPid, ToPid, Reason}
            if (tuple.arity() >= 4) {
                Distribution.exitReceived(tuple.element(2).toPID(), tuple.element(3).toPID(),
                    tuple.element(4), true);
            }
            break;
        case MONITOR_P: // {19, FromPid, ToProc, Ref}
            if (tuple.arity() >= 4) {
                PID from = tuple.element(2).toPID();
                Term to = tuple.element(3);
                Reference reference = tuple.element(4).toReference();
                if (!Distribution.monitorReceived(from, to, reference)) {
                    sendMonitorExit(to, from, reference, NOPROC);
                }
            }
            break;
        case DEMONITOR_P: // {20, FromPid, ToProc, Ref}
            if (tuple.arity() >= 4) {
                Distribution.demonitorReceived(tuple.element(2).toPID(), tuple.element(3),
                    tuple.element(4).toReference());
            }
            break;
        case MONITOR_P_EXIT: // {21, FromProc, ToPid, Ref, Reason}
            if (tuple.arity() >= 5) {
                Distribution.monitorExitReceived(peer, tuple.element(2), tuple.element(3).toPID(),
                    tuple.element(4).toReference(), tuple.element(5));
            }
            break;
        case SPAWN_REQUEST: // {29, ReqId, From, GroupLeader, {M,F,A}, Opts} + Args
            if (tuple.arity() >= 6) {
                SpawnRequests.received(this, peer, tuple.element(2).toReference(),
                    tuple.element(3).toPID(), tuple.element(5).toTuple(),
                    tuple.element(6).toList(), new ExternalTermReader(terms).read());
            }
            break;
        case SPAWN_REPLY: // {31, ReqId, To, Flags, Result}
            if (tuple.arity() >= 5) {
                SpawnRequests.replyReceived(peer, tuple.element(2).toReference(),
                    tuple.element(3).toPID(), tuple.element(4).toInteger().toInt(),
                    tuple.element(5));
            }
            break;
        case UNLINK_ID: // {35, Id, FromPid, ToPid}
            if (tuple.arity() >= 4) {
                Integer id = tuple.element(2).toInteger();
                PID from = tuple.element(3).toPID();
                PID to = tuple.element(4).toPID();
                // The acknowledgement has to reach the peer before any
                // other signal does, so it is written here, on the reader
                // thread that received the request.
                sendUnlinkAck(to, from, id);
                Distribution.unlinkIdReceived(id.toLong(), from, to);
            }
            break;
        case UNLINK_ID_ACK: // {36, Id, FromPid, ToPid}
            if (tuple.arity() >= 4) {
                Distribution.unlinkIdAckReceived(tuple.element(2).toInteger().toLong(),
                    tuple.element(3).toPID(), tuple.element(4).toPID());
            }
            break;
        default:
            // NODE_LINK and NODE_LINK signals that this node does not act on
            // do not carry a message.
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
            long timeout = tickTimeMillis();
            long interval = Math.max(100, timeout / 4);
            try {
                Thread.sleep(interval);
                if (!open) {
                    return;
                }
                if (System.currentTimeMillis() - lastReceived > timeout) {
                    debug("no traffic from " + peer + " for " + timeout + " ms");
                    close(Atom.of("net_tick_timeout"));
                    return;
                }
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

    /**
     * Net tick time in milliseconds. The default matches Erlang/OTP's
     * 60 second net_ticktime; tests can shorten it with the
     * jerlang.dist.ticktime system property (in seconds).
     */
    private static long tickTimeMillis() {
        int seconds = java.lang.Integer.getInteger("jerlang.dist.ticktime",
            DEFAULT_TICK_TIME_SECONDS);
        if (seconds <= 0) {
            seconds = DEFAULT_TICK_TIME_SECONDS;
        }
        return seconds * 1000L;
    }

    public void close() {
        close(Atom.of("connection_closed"));
    }

    public void close(Term reason) {
        if (!open) {
            return;
        }
        closeReason = reason;
        open = false;
        try {
            socket.close();
        } catch (IOException ignored) {
            // Closing is best effort.
        }
        Distribution.remove(this);
        debug("closed connection to " + peer + ": " + reason);
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
