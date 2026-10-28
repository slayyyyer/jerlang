package org.jerlang.kernel.inet;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import org.jerlang.ProcessRegistry;
import org.jerlang.type.PID;
import org.jerlang.type.PortID;
import org.jerlang.type.Term;

/**
 * Registry of the TCP sockets this virtual machine owns. Erlang sees a
 * PortID; the Java side maps it to the socket state.
 */
public final class InetSockets {

    private static final AtomicInteger NEXT_PORT = new AtomicInteger(1);
    private static final Map<PortID, InetSocket> SOCKETS = new ConcurrentHashMap<>();

    private InetSockets() {
    }

    public static PortID register(ServerSocket server, Integer boundPort, InetOptions options,
        PID owner) throws IOException {
        PortID id = nextId();
        InetSocket socket = new InetSocket(id, server, null, options, owner);
        SOCKETS.put(id, socket);
        ProcessRegistry.registerPort(new TcpPort(id, socket));
        return id;
    }

    public static InetSocket register(Socket socket, Integer ignored, InetOptions options, PID owner)
        throws IOException {
        PortID id = nextId();
        InetSocket result = new InetSocket(id, null, socket, options, owner);
        SOCKETS.put(id, result);
        ProcessRegistry.registerPort(new TcpPort(id, result));
        return result;
    }

    public static InetSocket get(Term term) {
        if (!(term instanceof PortID)) {
            return null;
        }
        return SOCKETS.get(term);
    }

    public static void remove(PortID id) {
        SOCKETS.remove(id);
    }

    private static PortID nextId() {
        return new PortID(NEXT_PORT.getAndIncrement());
    }

}
