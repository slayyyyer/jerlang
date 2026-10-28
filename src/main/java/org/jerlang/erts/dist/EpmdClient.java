package org.jerlang.erts.dist;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

/**
 * Client for the Erlang Port Mapper Daemon, enough to find the
 * distribution port of another node.
 *
 * See:
 * http://www.erlang.org/doc/apps/erts/erl_dist_protocol.html#epmd-protocol
 */
public final class EpmdClient {

    private static final int DEFAULT_PORT = 4369;
    private static final int TIMEOUT = 5000;
    private static final int NORMAL_NODE = 77;
    private static final int TCP_IPV4 = 0;

    private EpmdClient() {
    }

    /**
     * Register a node with EPMD and keep the returned socket open. Closing
     * the socket unregisters the node again, as described by the EPMD
     * protocol.
     *
     * @return the connection whose lifetime is the registration
     */
    public static Socket register(String host, String nodeName, int port) throws IOException {
        int epmdPort = Integer.getInteger("jerlang.epmd.port", DEFAULT_PORT);
        byte[] name = nodeName.getBytes(StandardCharsets.UTF_8);

        Socket socket = new Socket();
        try {
            socket.connect(new InetSocketAddress(host, epmdPort), TIMEOUT);
            socket.setSoTimeout(TIMEOUT);

            DataOutputStream out = new DataOutputStream(socket.getOutputStream());
            ByteArrayOutputStream requestBuffer = new ByteArrayOutputStream();
            DataOutputStream request = new DataOutputStream(requestBuffer);
            request.writeByte('x'); // ALIVE2_REQ
            request.writeShort(port);
            request.writeByte(NORMAL_NODE);
            request.writeByte(TCP_IPV4);
            request.writeShort(6); // highest distribution version
            request.writeShort(6); // lowest distribution version
            request.writeShort(name.length);
            request.write(name);
            request.writeShort(0); // no extra field

            byte[] payload = requestBuffer.toByteArray();
            out.writeShort(payload.length);
            out.write(payload);
            out.flush();

            DataInputStream response = new DataInputStream(
                new BufferedInputStream(socket.getInputStream()));
            int tag = response.readUnsignedByte();
            if (tag != 'v' && tag != 'y') { // v: 32-bit creation, y: 16-bit
                throw new IOException("EPMD sent an unexpected ALIVE2 response tag " + tag);
            }
            int result = response.readUnsignedByte();
            if (result != 0) {
                throw new IOException("EPMD refused to register node " + nodeName
                    + " (result " + result + ")");
            }
            if (tag == 'v') {
                response.readInt();
            } else {
                response.readUnsignedShort();
            }
            socket.setSoTimeout(0);
            return socket;
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
     * Ask the EPMD on host for the distribution port of nodeName.
     *
     * The node name in EPMD is the part of a full node name before the
     * @ sign.
     */
    public static int port(String host, String nodeName) throws IOException {
        int epmdPort = Integer.getInteger("jerlang.epmd.port", DEFAULT_PORT);
        byte[] name = nodeName.getBytes(StandardCharsets.UTF_8);

        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(host, epmdPort), TIMEOUT);
            socket.setSoTimeout(TIMEOUT);

            DataOutputStream out = new DataOutputStream(socket.getOutputStream());
            // PORT2_REQ is short framed: two byte length, the tag and the name.
            out.writeShort(1 + name.length);
            out.writeByte('z');
            out.write(name);
            out.flush();

            // Unlike the request, the PORT2_RESP is not length framed.
            DataInputStream response = new DataInputStream(
                new BufferedInputStream(socket.getInputStream()));
            int tag = response.readUnsignedByte();
            if (tag != 'w') {
                throw new IOException("EPMD sent an unexpected response tag " + tag);
            }
            int result = response.readUnsignedByte();
            if (result != 0) {
                throw new IOException("EPMD does not know node " + nodeName
                    + " (result " + result + ")");
            }

            int port = response.readUnsignedShort();
            response.readUnsignedByte(); // node type
            response.readUnsignedByte(); // protocol
            response.readUnsignedShort(); // highest version
            response.readUnsignedShort(); // lowest version
            response.readUnsignedShort(); // node name length
            return port;
        }
    }

}
