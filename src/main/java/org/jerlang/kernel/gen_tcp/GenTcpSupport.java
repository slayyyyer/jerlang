package org.jerlang.kernel.gen_tcp;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;

import org.jerlang.erts.erlang.Error;
import org.jerlang.kernel.inet.InetOptions;
import org.jerlang.kernel.inet.InetSocket;
import org.jerlang.kernel.inet.InetSockets;
import org.jerlang.kernel.inet.InetSupport;
import org.jerlang.kernel.inet.TcpPort;
import org.jerlang.type.Atom;
import org.jerlang.type.PID;
import org.jerlang.type.PortID;
import org.jerlang.type.Term;
import org.jerlang.type.Tuple;

/**
 * Native implementation of the gen_tcp functions.
 */
public final class GenTcpSupport {

    private static final Atom ok = Atom.of("ok");
    private static final Atom closed = Atom.of("closed");
    private static final Atom einval = Atom.of("einval");

    private GenTcpSupport() {
    }

    public static Term listen(Term portTerm, Term optionsTerm) {
        int port = InetSupport.integer(portTerm);
        InetOptions options = InetOptions.parse(optionsTerm.toList());
        ServerSocket server = null;
        try {
            server = new ServerSocket();
            options.applyToListener(server);
            InetAddress address = InetSupport.bindAddress(options);
            server.bind(new InetSocketAddress(address, port), options.backlog);
            PortID id = InetSockets.register(server, port, options, null);
            return InetSupport.ok(id);
        } catch (IOException e) {
            closeQuietly(server);
            return InetSupport.error(InetSupport.reason(e));
        }
    }

    public static Term accept(Term socketTerm, Term timeoutTerm) {
        InetSocket listener = InetSupport.listener(socketTerm);
        try {
            InetSocket accepted = listener.accept(InetSupport.timeout(timeoutTerm),
                InetSupport.selfPid());
            return InetSupport.ok(accepted.id());
        } catch (java.net.SocketTimeoutException e) {
            return InetSupport.error(Atom.of("timeout"));
        } catch (IOException e) {
            return InetSupport.error(InetSupport.reason(e));
        }
    }

    public static Term connect(Term addressTerm, Term portTerm, Term optionsTerm,
        Term timeoutTerm) {
        int port = InetSupport.integer(portTerm);
        InetOptions options = InetOptions.parse(optionsTerm.toList());
        InetAddress address = InetOptions.toAddress(addressTerm);
        int timeout = InetSupport.timeout(timeoutTerm);

        Socket socket = new Socket();
        try {
            socket.setReuseAddress(options.reuseaddr);
            socket.connect(new InetSocketAddress(address, port), timeout);
            options.applyToSocket(socket);
            InetSocket connected = InetSockets.register(socket, port, options,
                InetSupport.selfPid());
            connected.start();
            return InetSupport.ok(connected.id());
        } catch (IOException | RuntimeException e) {
            closeQuietly(socket);
            return InetSupport.error(InetSupport.reason(e));
        }
    }

    public static Term send(Term socketTerm, Term dataTerm) {
        InetSocket socket = InetSupport.connected(socketTerm);
        return socket.send(InetSupport.data(dataTerm));
    }

    public static Term recv(Term socketTerm, Term lengthTerm, Term timeoutTerm) {
        InetSocket socket = InetSupport.connected(socketTerm);
        return socket.recv(InetSupport.integer(lengthTerm), InetSupport.timeout(timeoutTerm));
    }

    public static Term close(Term socketTerm) {
        if (!(socketTerm instanceof PortID)) {
            throw Error.badarg;
        }
        InetSocket socket = InetSockets.get(socketTerm);
        // The reader closes a socket when its peer goes away. An explicit
        // close of that already closed socket is a no-op, like in OTP.
        if (socket != null) {
            socket.close();
        }
        return ok;
    }

    public static Term shutdown(Term socketTerm, Term howTerm) {
        InetSocket socket = InetSupport.connected(socketTerm);
        if (!(howTerm instanceof Atom)) {
            throw Error.badarg;
        }
        return socket.shutdown((Atom) howTerm);
    }

    public static Term controllingProcess(Term socketTerm, Term pidTerm) {
        InetSocket socket = InetSupport.socket(socketTerm);
        if (!(pidTerm instanceof PID)) {
            throw Error.badarg;
        }
        socket.controllingProcess((PID) pidTerm);
        return ok;
    }

    static Term notConnected() {
        return InetSupport.error(closed);
    }

    static Term invalid() {
        return InetSupport.error(einval);
    }

    private static void closeQuietly(ServerSocket server) {
        if (server == null) {
            return;
        }
        try {
            server.close();
        } catch (IOException ignored) {
            // The original failure is the interesting one.
        }
    }

    private static void closeQuietly(Socket socket) {
        try {
            socket.close();
        } catch (IOException ignored) {
            // The original failure is the interesting one.
        }
    }

}
