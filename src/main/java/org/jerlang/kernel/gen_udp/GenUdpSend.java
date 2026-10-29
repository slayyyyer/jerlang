package org.jerlang.kernel.gen_udp;

import org.jerlang.erts.erlang.Error;
import org.jerlang.type.List;
import org.jerlang.type.Term;

public class GenUdpSend {

    public static Term dispatch(List params) {
        switch (params.length()) {
        case 2:
            return GenUdpSupport.send(params.head(), params.tail().head());
        case 4:
            return GenUdpSupport.send(params.head(), params.tail().head(),
                params.tail().tail().head(), params.tail().tail().tail().head());
        default:
            throw Error.badarg;
        }
    }

}
