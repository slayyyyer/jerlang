package org.jerlang.kernel.inet;

import java.net.MulticastSocket;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.jerlang.ProcessRegistry;
import org.jerlang.type.PID;
import org.jerlang.type.PortID;
import org.jerlang.type.Term;

/**
 * Registry of the UDP sockets this virtual machine owns. Erlang sees a
 * PortID, like it does for gen_tcp sockets.
 */
public final class UdpSockets {

    private static final Map<PortID, UdpSocket> SOCKETS = new ConcurrentHashMap<>();

    private UdpSockets() {
    }

    public static UdpSocket register(MulticastSocket socket, InetOptions options, PID owner) {
        PortID id = InetPortIds.next();
        UdpSocket result = new UdpSocket(id, socket, options, owner);
        SOCKETS.put(id, result);
        ProcessRegistry.registerPort(new UdpPort(id, result));
        return result;
    }

    public static UdpSocket get(Term term) {
        if (!(term instanceof PortID)) {
            return null;
        }
        return SOCKETS.get(term);
    }

    public static void remove(PortID id) {
        SOCKETS.remove(id);
    }

}
