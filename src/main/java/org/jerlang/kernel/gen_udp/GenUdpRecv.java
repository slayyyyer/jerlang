package org.jerlang.kernel.gen_udp;

import org.jerlang.erts.erlang.Error;
import org.jerlang.type.Atom;
import org.jerlang.type.List;
import org.jerlang.type.Term;

public class GenUdpRecv {

    public static Term dispatch(List params) {
        switch (params.length()) {
        case 2:
            return GenUdpSupport.recv(params.head(), params.tail().head(),
                Atom.of("infinity"));
        case 3:
            return GenUdpSupport.recv(params.head(), params.tail().head(),
                params.tail().tail().head());
        default:
            throw Error.badarg;
        }
    }

}
