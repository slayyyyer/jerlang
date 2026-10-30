package org.jerlang.stdlib.code;

import org.jerlang.erts.erlang.Error;
import org.jerlang.stdlib.Code;
import org.jerlang.type.List;
import org.jerlang.type.Term;

/**
 * code:replace_path/2
 */
public class CodeReplacePath {

    public static Term dispatch(List params) {
        switch (params.length()) {
        case 2:
            return Code.replace_path(params.head(), params.tail().head());
        default:
            throw Error.badarg;
        }
    }

}
