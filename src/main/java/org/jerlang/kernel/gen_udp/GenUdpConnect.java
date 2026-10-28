package org.jerlang.kernel.gen_udp;

import org.jerlang.erts.erlang.Error;
import org.jerlang.type.List;
import org.jerlang.type.Term;

public class GenUdpConnect {

    public static Term dispatch(List params) {
        if (params.length() != 3) {
            throw Error.badarg;
        }
        return GenUdpSupport.connect(params.head(), params.tail().head(),
            params.tail().tail().head());
    }

}
