package org.jerlang.stdlib.code;

import org.jerlang.erts.erlang.Error;
import org.jerlang.stdlib.Code;
import org.jerlang.type.List;
import org.jerlang.type.Term;

/**
 * code:is_sticky/1
 */
public class CodeIsSticky {

    public static Term dispatch(List params) {
        switch (params.length()) {
        case 1:
            return Code.is_sticky(params.head());
        default:
            throw Error.badarg;
        }
    }

}
