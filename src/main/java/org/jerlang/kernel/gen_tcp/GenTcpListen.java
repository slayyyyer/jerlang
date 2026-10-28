package org.jerlang.kernel.gen_tcp;

import org.jerlang.erts.erlang.Error;
import org.jerlang.type.List;
import org.jerlang.type.Term;

public class GenTcpListen {

    public static Term dispatch(List params) {
        switch (params.length()) {
        case 1:
            return GenTcpSupport.listen(params.head(), List.nil);
        case 2:
            return GenTcpSupport.listen(params.head(), params.tail().head());
        default:
            throw Error.badarg;
        }
    }

}
