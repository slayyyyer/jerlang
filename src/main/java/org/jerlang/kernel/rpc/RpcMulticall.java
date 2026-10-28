package org.jerlang.kernel.rpc;

import org.jerlang.erts.erlang.Error;
import org.jerlang.kernel.Rpc;
import org.jerlang.type.List;
import org.jerlang.type.Term;

public class RpcMulticall {

    private RpcMulticall() {
    }

    public static Term dispatch(List params) {
        switch (params.length()) {
        case 3:
            return Rpc.multicall(params.head(), params.tail().head(),
                params.tail().tail().head());
        case 4:
            return Rpc.multicall(params.head(), params.tail().head(),
                params.tail().tail().head(), params.tail().tail().tail().head());
        case 5:
            return Rpc.multicall(params.head(), params.tail().head(),
                params.tail().tail().head(), params.tail().tail().tail().head(),
                params.tail().tail().tail().tail().head());
        default:
            throw Error.badarg;
        }
    }

}
