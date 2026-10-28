package org.jerlang.kernel.gen_tcp;

import org.jerlang.erts.erlang.Error;
import org.jerlang.type.Atom;
import org.jerlang.type.List;
import org.jerlang.type.Term;

public class GenTcpAccept {

    public static Term dispatch(List params) {
        switch (params.length()) {
        case 1:
            return GenTcpSupport.accept(params.head(), Atom.of("infinity"));
        case 2:
            return GenTcpSupport.accept(params.head(), params.tail().head());
        default:
            throw Error.badarg;
        }
    }

}
