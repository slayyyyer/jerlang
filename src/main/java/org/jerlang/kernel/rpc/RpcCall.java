package org.jerlang.kernel.rpc;

import org.jerlang.erts.erlang.Error;
import org.jerlang.kernel.Rpc;
import org.jerlang.type.List;
import org.jerlang.type.Term;

public class RpcCall {

    private RpcCall() {
    }

    public static Term dispatch(List params) {
        switch (params.length()) {
        case 4:
            return Rpc.call(params.head(), params.tail().head(),
                params.tail().tail().head(), params.tail().tail().tail().head());
        case 5:
            return Rpc.call(params.head(), params.tail().head(),
                params.tail().tail().head(), params.tail().tail().tail().head(),
                params.tail().tail().tail().tail().head());
        default:
            throw Error.badarg;
        }
    }

}
