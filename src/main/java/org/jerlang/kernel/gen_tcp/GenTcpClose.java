package org.jerlang.kernel.gen_tcp;

import org.jerlang.erts.erlang.Error;
import org.jerlang.type.List;
import org.jerlang.type.Term;

public class GenTcpClose {

    public static Term dispatch(List params) {
        if (params.length() != 1) {
            throw Error.badarg;
        }
        return GenTcpSupport.close(params.head());
    }

}
