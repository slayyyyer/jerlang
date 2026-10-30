package org.jerlang.stdlib.io;

import org.jerlang.erts.erlang.Error;
import org.jerlang.type.Atom;
import org.jerlang.type.List;
import org.jerlang.type.Term;

public class IOFormat {

    private static final Atom STANDARD_IO = Atom.of("standard_io");

    private IOFormat() {
    }

    public static Term dispatch(List params) {
        switch (params.length()) {
        case 1:
            return FormatEngine.formatTo(STANDARD_IO, params.head(), List.nil);
        case 2:
            return FormatEngine.formatTo(STANDARD_IO, params.head(), params.tail().head());
        case 3:
            return FormatEngine.formatTo(params.head(), params.tail().head(),
                params.tail().tail().head());
        default:
            throw Error.badarg;
        }
    }

}
