package org.jerlang.kernel.gen_udp;

import org.jerlang.erts.erlang.Error;
import org.jerlang.type.List;
import org.jerlang.type.Term;

public class GenUdpOpen {

    public static Term dispatch(List params) {
        switch (params.length()) {
        case 1:
            return GenUdpSupport.open(params.head(), List.nil);
        case 2:
            return GenUdpSupport.open(params.head(), params.tail().head());
        default:
            throw Error.badarg;
        }
    }

}
