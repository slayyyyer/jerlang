package org.jerlang.erts.erpc;

import org.jerlang.erts.Erpc;
import org.jerlang.erts.erlang.Error;
import org.jerlang.type.List;
import org.jerlang.type.Term;

public class ErpcCall {

    private ErpcCall() {
    }

    public static Term dispatch(List params) {
        switch (params.length()) {
        case 4:
            return Erpc.call(params.head(), params.tail().head(),
                params.tail().tail().head(), params.tail().tail().tail().head());
        case 5:
            return Erpc.call(params.head(), params.tail().head(),
                params.tail().tail().head(), params.tail().tail().tail().head(),
                params.tail().tail().tail().tail().head());
        default:
            throw Error.badarg;
        }
    }

}
