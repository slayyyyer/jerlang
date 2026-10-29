package org.jerlang.kernel.inet;

import org.jerlang.Port;
import org.jerlang.ProcessState;
import org.jerlang.type.PortID;

/**
 * The Erlang port object behind a gen_udp socket. It carries no driver;
 * the Java datagram socket is managed by UdpSocket.
 */
public final class UdpPort extends Port {

    private final UdpSocket socket;

    public UdpPort(PortID id, UdpSocket socket) {
        super(id, null);
        this.socket = socket;
    }

    @Override
    public void execute() {
        setState(ProcessState.WAITING);
    }

    @Override
    public void close() {
        socket.close();
    }

}
