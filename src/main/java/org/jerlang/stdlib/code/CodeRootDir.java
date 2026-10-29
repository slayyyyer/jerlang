package org.jerlang.stdlib.code;

import org.jerlang.erts.erlang.Error;
import org.jerlang.stdlib.Code;
import org.jerlang.type.List;
import org.jerlang.type.Term;

public class CodeRootDir {

    private CodeRootDir() {
    }

    public static Term dispatch(List params) {
        switch (params.length()) {
        case 0:
            return Code.root_dir();
        default:
            throw Error.badarg;
        }
    }

}
