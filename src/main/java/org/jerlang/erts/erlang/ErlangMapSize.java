package org.jerlang.erts.erlang;

import org.jerlang.type.Integer;
import org.jerlang.type.List;
import org.jerlang.type.Map;
import org.jerlang.type.Term;

/**
 * http://www.erlang.org/doc/man/erlang.html#map_size-1
 */
public class ErlangMapSize {

    private ErlangMapSize() {
    }

    public static Term dispatch(List params) {
        switch (params.length()) {
        case 1:
            return map_size_1(params.head().toMap());
        default:
            throw Error.badarg;
        }
    }

    public static Integer map_size_1(Map map) {
        return map.size();
    }

}
