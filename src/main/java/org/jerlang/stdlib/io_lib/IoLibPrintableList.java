package org.jerlang.stdlib.io_lib;

import org.jerlang.erts.erlang.Error;
import org.jerlang.type.Boolean;
import org.jerlang.type.List;
import org.jerlang.type.Term;

public class IoLibPrintableList {

    private IoLibPrintableList() {
    }

    public static Term dispatch(List params) {
        switch (params.length()) {
        case 1:
            return printable_list_1(params.head());
        default:
            throw Error.badarg;
        }
    }

    public static Term printable_list_1(Term data) {
        return Boolean.of(Printable.latin1(data));
    }

}
