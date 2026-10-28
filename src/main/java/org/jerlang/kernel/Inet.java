package org.jerlang.kernel;

import org.jerlang.kernel.inet.InetSocket;
import org.jerlang.kernel.inet.InetSupport;
import org.jerlang.type.Atom;
import org.jerlang.type.Term;

/**
 * = inet
 *
 * Access to the options and names of TCP sockets created by gen_tcp.
 *
 * http://www.erlang.org/doc/man/inet.html
 */
public class Inet {

    private Inet() {
    }

    public static Term close(Term socket) {
        InetSocket s = InetSupport.socket(socket);
        s.close();
        return Atom.of("ok");
    }

    public static Term getopts(Term socket, Term options) {
        return InetSupport.socket(socket).getopts(options.toList());
    }

    public static Term ntoa(Term address) {
        return InetSupport.ntoa(address);
    }

    public static Term parse_address(Term address) {
        return InetSupport.parseAddress(address);
    }

    public static Term peername(Term socket) {
        return InetSupport.connected(socket).peername();
    }

    public static Term port(Term socket) {
        return InetSupport.socket(socket).port();
    }

    public static Term setopts(Term socket, Term options) {
        return InetSupport.socket(socket).setopts(options.toList());
    }

    public static Term sockname(Term socket) {
        return InetSupport.socket(socket).sockname();
    }

}
