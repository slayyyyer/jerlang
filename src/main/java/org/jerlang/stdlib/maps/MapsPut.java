package org.jerlang.stdlib.maps;

import org.jerlang.erts.erlang.Error;
import org.jerlang.type.List;
import org.jerlang.type.Map;
import org.jerlang.type.Term;

public class MapsPut {

    private MapsPut() {
    }

    public static Term dispatch(List params) {
        switch (params.length()) {
        case 3:
            return put_3(params.head(), params.tail().head(), params.tail().tail().head().toMap());
        default:
            throw Error.badarg;
        }
    }

    /**
     * Associates Key with Value in a copy of Map. Maps are values: the
     * argument must not change.
     *
     * http://www.erlang.org/doc/man/maps.html#put-3
     */
    public static Map put_3(Term key, Term value, Map map) {
        Map result = new Map(map);
        result.set(key, value);
        return result;
    }

}
