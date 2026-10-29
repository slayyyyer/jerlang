package org.jerlang.erts.erpc;

import org.jerlang.erts.Erpc;
import org.jerlang.erts.erlang.Error;
import org.jerlang.type.List;
import org.jerlang.type.Term;

public class ErpcExecuteCall {

    private ErpcExecuteCall() {
    }

    public static Term dispatch(List params) {
        if (params.length() != 4) {
            throw Error.badarg;
        }
        return Erpc.execute_call(params.head(), params.tail().head(),
            params.tail().tail().head(), params.tail().tail().tail().head());
    }

}
