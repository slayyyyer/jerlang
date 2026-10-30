package org.jerlang.stdlib.io_lib;

import org.jerlang.erts.erlang.Error;
import org.jerlang.stdlib.io.FormatEngine;
import org.jerlang.type.List;
import org.jerlang.type.Str;
import org.jerlang.type.Term;

public class IoLibFormat {

    private IoLibFormat() {
    }

    public static Term dispatch(List params) {
        switch (params.length()) {
        case 2:
            return format_2(params.head(), params.tail().head());
        default:
            throw Error.badarg;
        }
    }

    /**
     * http://www.erlang.org/doc/man/io_lib.html#format-2
     */
    public static Term format_2(Term format, Term arguments) {
        return Str.of(FormatEngine.format(format, arguments));
    }

}
