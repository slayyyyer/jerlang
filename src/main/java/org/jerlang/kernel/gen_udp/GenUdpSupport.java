package org.jerlang.kernel.gen_udp;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.MulticastSocket;

import org.jerlang.erts.erlang.Error;
import org.jerlang.kernel.inet.InetOptions;
import org.jerlang.kernel.inet.InetSupport;
import org.jerlang.kernel.inet.UdpSocket;
import org.jerlang.kernel.inet.UdpSockets;
import org.jerlang.type.Atom;
import org.jerlang.type.PID;
import org.jerlang.type.PortID;
import org.jerlang.type.Term;

/**
 * Native implementation of the gen_udp functions.
 */
public final class GenUdpSupport {

    private static final Atom ok = Atom.of("ok");

    private GenUdpSupport() {
    }

    public static Term open(Term portTerm, Term optionsTerm) {
        int port = InetSupport.integer(portTerm);
        InetOptions options = InetOptions.parse(optionsTerm.toList());
        MulticastSocket socket = null;
        UdpSocket udp = null;
        try {
            socket = new MulticastSocket((InetSocketAddress) null);
            socket.setReuseAddress(options.reuseaddr);
            InetAddress address = InetSupport.bindAddress(options);
            socket.bind(new InetSocketAddress(address, port));
            udp = UdpSockets.register(socket, options, InetSupport.selfPid());
            udp.applyMulticastOptions(options);
            udp.start();
            return InetSupport.ok(udp.id());
        } catch (IOException | RuntimeException e) {
            if (udp != null) {
                udp.close();
            } else {
                closeQuietly(socket);
            }
            return InetSupport.error(InetSupport.reason(e));
        }
    }

    public static Term close(Term socketTerm) {
        if (!(socketTerm instanceof PortID)) {
            throw Error.badarg;
        }
        UdpSocket socket = UdpSockets.get(socketTerm);
        if (socket != null) {
            socket.close();
        }
        return ok;
    }

    public static Term send(Term socketTerm, Term addressTerm, Term portTerm, Term dataTerm) {
        UdpSocket socket = InetSupport.udp(socketTerm);
        InetAddress address = InetOptions.toAddress(addressTerm);
        return socket.send(address, InetSupport.integer(portTerm),
            InetSupport.data(dataTerm));
    }

    public static Term send(Term socketTerm, Term dataTerm) {
        UdpSocket socket = InetSupport.udp(socketTerm);
        return socket.send(InetSupport.data(dataTerm));
    }

    public static Term recv(Term socketTerm, Term lengthTerm, Term timeoutTerm) {
        UdpSocket socket = InetSupport.udp(socketTerm);
        return socket.recv(InetSupport.integer(lengthTerm),
            InetSupport.timeout(timeoutTerm));
    }

    public static Term connect(Term socketTerm, Term addressTerm, Term portTerm) {
        UdpSocket socket = InetSupport.udp(socketTerm);
        InetAddress address = InetOptions.toAddress(addressTerm);
        return socket.connect(address, InetSupport.integer(portTerm));
    }

    public static Term controllingProcess(Term socketTerm, Term pidTerm) {
        UdpSocket socket = InetSupport.udp(socketTerm);
        if (!(pidTerm instanceof PID)) {
            throw Error.badarg;
        }
        socket.controllingProcess((PID) pidTerm);
        return ok;
    }

    private static void closeQuietly(MulticastSocket socket) {
        if (socket != null) {
            socket.close();
        }
    }

}
