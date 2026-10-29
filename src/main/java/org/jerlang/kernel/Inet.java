package org.jerlang.kernel;

import org.jerlang.kernel.inet.InetEndpoint;
import org.jerlang.kernel.inet.InetSupport;
import org.jerlang.type.Atom;
import org.jerlang.type.Term;

/**
 * = inet
 *
 * Access to the options and names of TCP and UDP sockets created by
 * gen_tcp and gen_udp.
 *
 * http://www.erlang.org/doc/man/inet.html
 */
public class Inet {

    private Inet() {
    }

    public static Term close(Term socket) {
        InetEndpoint s = InetSupport.endpoint(socket);
        s.close();
        return Atom.of("ok");
    }

    public static Term getopts(Term socket, Term options) {
        return InetSupport.endpoint(socket).getopts(options.toList());
    }

    public static Term ntoa(Term address) {
        return InetSupport.ntoa(address);
    }

    public static Term parse_address(Term address) {
        return InetSupport.parseAddress(address);
    }

    public static Term peername(Term socket) {
        return InetSupport.endpoint(socket).peername();
    }

    public static Term port(Term socket) {
        return InetSupport.endpoint(socket).port();
    }

    public static Term setopts(Term socket, Term options) {
        return InetSupport.endpoint(socket).setopts(options.toList());
    }

    public static Term sockname(Term socket) {
        return InetSupport.endpoint(socket).sockname();
    }

}
