package org.jerlang.kernel.gen_tcp;

import org.jerlang.erts.erlang.Error;
import org.jerlang.type.Atom;
import org.jerlang.type.List;
import org.jerlang.type.Term;

public class GenTcpConnect {

    public static Term dispatch(List params) {
        switch (params.length()) {
        case 3:
            return GenTcpSupport.connect(params.head(), params.tail().head(),
                params.tail().tail().head(), Atom.of("infinity"));
        case 4:
            return GenTcpSupport.connect(params.head(), params.tail().head(),
                params.tail().tail().head(), params.tail().tail().tail().head());
        default:
            throw Error.badarg;
        }
    }

}
