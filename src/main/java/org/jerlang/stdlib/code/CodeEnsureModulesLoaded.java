package org.jerlang.stdlib.code;

import org.jerlang.erts.erlang.Error;
import org.jerlang.stdlib.Code;
import org.jerlang.type.List;
import org.jerlang.type.Term;

/**
 * code:ensure_modules_loaded/1
 */
public class CodeEnsureModulesLoaded {

    public static Term dispatch(List params) {
        switch (params.length()) {
        case 1:
            return Code.ensure_modules_loaded(params.head());
        default:
            throw Error.badarg;
        }
    }

}
