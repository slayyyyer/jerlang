package org.jerlang.stdlib.code;

import org.jerlang.erts.erlang.Error;
import org.jerlang.stdlib.Code;
import org.jerlang.type.List;
import org.jerlang.type.Term;

public class CodePrivDir {

    private CodePrivDir() {
    }

    public static Term dispatch(List params) {
        switch (params.length()) {
        case 1:
            return Code.priv_dir(params.head().toAtom());
        default:
            throw Error.badarg;
        }
    }

}
