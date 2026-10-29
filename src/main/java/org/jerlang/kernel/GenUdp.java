package org.jerlang.kernel;

import org.jerlang.kernel.gen_udp.GenUdpSupport;
import org.jerlang.type.Atom;
import org.jerlang.type.List;
import org.jerlang.type.Term;

/**
 * = gen_udp
 *
 * Interface to UDP sockets, implemented natively on
 * java.net.DatagramSocket.
 *
 * http://www.erlang.org/doc/man/gen_udp.html
 */
public class GenUdp {

    private GenUdp() {
    }

    public static Term open(Term port) {
        return GenUdpSupport.open(port, List.nil);
    }

    public static Term open(Term port, Term options) {
        return GenUdpSupport.open(port, options);
    }

    public static Term close(Term socket) {
        return GenUdpSupport.close(socket);
    }

    public static Term connect(Term socket, Term address, Term port) {
        return GenUdpSupport.connect(socket, address, port);
    }

    public static Term controlling_process(Term socket, Term pid) {
        return GenUdpSupport.controllingProcess(socket, pid);
    }

    public static Term recv(Term socket, Term length) {
        return GenUdpSupport.recv(socket, length, Atom.of("infinity"));
    }

    public static Term recv(Term socket, Term length, Term timeout) {
        return GenUdpSupport.recv(socket, length, timeout);
    }

    public static Term send(Term socket, Term data) {
        return GenUdpSupport.send(socket, data);
    }

    public static Term send(Term socket, Term address, Term port, Term data) {
        return GenUdpSupport.send(socket, address, port, data);
    }

}
