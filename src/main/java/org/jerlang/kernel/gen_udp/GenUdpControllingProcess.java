package org.jerlang.kernel.gen_udp;

import org.jerlang.erts.erlang.Error;
import org.jerlang.type.List;
import org.jerlang.type.Term;

public class GenUdpControllingProcess {

    public static Term dispatch(List params) {
        if (params.length() != 2) {
            throw Error.badarg;
        }
        return GenUdpSupport.controllingProcess(params.head(), params.tail().head());
    }

}
