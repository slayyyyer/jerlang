package org.jerlang.erts.erpc;

import org.jerlang.erts.Erpc;
import org.jerlang.erts.erlang.Error;
import org.jerlang.type.List;
import org.jerlang.type.Term;

public class ErpcExecuteCast {

    private ErpcExecuteCast() {
    }

    public static Term dispatch(List params) {
        if (params.length() != 3) {
            throw Error.badarg;
        }
        return Erpc.execute_cast(params.head(), params.tail().head(),
            params.tail().tail().head());
    }

}
