package org.jerlang.stdlib.code;

import org.jerlang.erts.erlang.Error;
import org.jerlang.stdlib.Code;
import org.jerlang.type.List;
import org.jerlang.type.Term;

public class CodeWhich {

    private CodeWhich() {
    }

    public static Term dispatch(List params) {
        switch (params.length()) {
        case 1:
            return Code.which(params.head().toAtom());
        default:
            throw Error.badarg;
        }
    }

}
