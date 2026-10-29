package org.jerlang.kernel.rpc;

import org.jerlang.erts.erlang.Error;
import org.jerlang.kernel.Rpc;
import org.jerlang.type.List;
import org.jerlang.type.Term;

public class RpcAbcast {

    private RpcAbcast() {
    }

    public static Term dispatch(List params) {
        if (params.length() != 3) {
            throw Error.badarg;
        }
        return Rpc.abcast(params.head(), params.tail().head(),
            params.tail().tail().head());
    }

}
