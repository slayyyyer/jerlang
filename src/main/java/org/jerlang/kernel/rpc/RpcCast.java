package org.jerlang.kernel.rpc;

import org.jerlang.erts.erlang.Error;
import org.jerlang.kernel.Rpc;
import org.jerlang.type.List;
import org.jerlang.type.Term;

public class RpcCast {

    private RpcCast() {
    }

    public static Term dispatch(List params) {
        if (params.length() != 4) {
            throw Error.badarg;
        }
        return Rpc.cast(params.head(), params.tail().head(),
            params.tail().tail().head(), params.tail().tail().tail().head());
    }

}
