package org.jerlang.stdlib.code;

import org.jerlang.erts.erlang.Error;
import org.jerlang.stdlib.Code;
import org.jerlang.type.List;
import org.jerlang.type.Term;

public class CodeGetPath {

    private CodeGetPath() {
    }

    public static Term dispatch(List params) {
        switch (params.length()) {
        case 0:
            return Code.get_path();
        default:
            throw Error.badarg;
        }
    }

}
