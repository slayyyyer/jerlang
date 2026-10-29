package org.jerlang.stdlib.code;

import org.jerlang.erts.erlang.Error;
import org.jerlang.stdlib.Code;
import org.jerlang.type.List;
import org.jerlang.type.Term;

public class CodeLibDir {

    private CodeLibDir() {
    }

    public static Term dispatch(List params) {
        switch (params.length()) {
        case 1:
            return Code.lib_dir(params.head().toAtom());
        case 2:
            return Code.lib_dir(params.head().toAtom(), params.tail().head());
        default:
            throw Error.badarg;
        }
    }

}
