package org.jerlang.kernel.inet;

import org.jerlang.erts.erlang.Error;
import org.jerlang.type.List;
import org.jerlang.type.Term;

public class InetGetopts {

    public static Term dispatch(List params) {
        if (params.length() != 2) {
            throw Error.badarg;
        }
        return InetSupport.endpoint(params.head()).getopts(params.tail().head().toList());
    }

}
