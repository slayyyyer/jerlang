package org.jerlang.kernel.inet;

import org.jerlang.Port;
import org.jerlang.ProcessState;
import org.jerlang.type.PortID;

/**
 * The Erlang port object behind a gen_tcp socket. It carries no driver;
 * the Java socket is managed by InetSocket.
 */
public final class TcpPort extends Port {

    private final InetSocket socket;

    public TcpPort(PortID id, InetSocket socket) {
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
