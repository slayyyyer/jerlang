package org.jerlang.stdlib.code;

import org.jerlang.erts.erlang.Error;
import org.jerlang.stdlib.Code;
import org.jerlang.type.List;
import org.jerlang.type.Term;

/**
 * code:unstick_dir/1
 */
public class CodeUnstickDir {

    public static Term dispatch(List params) {
        switch (params.length()) {
        case 1:
            return Code.unstick_dir(params.head());
        default:
            throw Error.badarg;
        }
    }

}
