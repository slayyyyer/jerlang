package org.jerlang.kernel.inet;

import org.jerlang.type.List;
import org.jerlang.type.PortID;
import org.jerlang.type.Term;

/**
 * The socket operations shared by gen_tcp and gen_udp sockets. The inet
 * module works on either kind of port.
 */
public interface InetEndpoint {

    PortID id();

    Term setopts(List options);

    Term getopts(List options);

    Term sockname();

    Term peername();

    Term port();

    void close();

}
