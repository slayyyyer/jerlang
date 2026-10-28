package org.jerlang.kernel;

import org.jerlang.kernel.gen_tcp.GenTcpSupport;
import org.jerlang.type.Term;

/**
 * = gen_tcp
 *
 * Interface to TCP/IP sockets, implemented natively on java.net sockets.
 *
 * http://www.erlang.org/doc/man/gen_tcp.html
 */
public class GenTcp {

    private GenTcp() {
    }

    public static Term accept(Term socket) {
        return GenTcpSupport.accept(socket, org.jerlang.type.Atom.of("infinity"));
    }

    public static Term accept(Term socket, Term timeout) {
        return GenTcpSupport.accept(socket, timeout);
    }

    public static Term close(Term socket) {
        return GenTcpSupport.close(socket);
    }

    public static Term connect(Term address, Term port, Term options) {
        return GenTcpSupport.connect(address, port, options,
            org.jerlang.type.Atom.of("infinity"));
    }

    public static Term connect(Term address, Term port, Term options, Term timeout) {
        return GenTcpSupport.connect(address, port, options, timeout);
    }

    public static Term controlling_process(Term socket, Term pid) {
        return GenTcpSupport.controllingProcess(socket, pid);
    }

    public static Term listen(Term port, Term options) {
        return GenTcpSupport.listen(port, options);
    }

    public static Term recv(Term socket, Term length) {
        return GenTcpSupport.recv(socket, length, org.jerlang.type.Atom.of("infinity"));
    }

    public static Term recv(Term socket, Term length, Term timeout) {
        return GenTcpSupport.recv(socket, length, timeout);
    }

    public static Term send(Term socket, Term data) {
        return GenTcpSupport.send(socket, data);
    }

    public static Term shutdown(Term socket, Term how) {
        return GenTcpSupport.shutdown(socket, how);
    }

}
