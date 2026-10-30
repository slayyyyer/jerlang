package org.jerlang.stdlib.maps;

import org.jerlang.erts.erlang.Error;
import org.jerlang.type.List;
import org.jerlang.type.Map;
import org.jerlang.type.Term;

public class MapsWith {

    private MapsWith() {
    }

    public static Term dispatch(List params) {
        switch (params.length()) {
        case 2:
            return with_2(params.head().toList(), params.tail().head().toMap());
        default:
            throw Error.badarg;
        }
    }

    /**
     * Returns a map with only the given keys. Keys that are not in the map
     * are ignored.
     *
     * http://www.erlang.org/doc/man/maps.html#with-2
     */
    public static Map with_2(List keys, Map map) {
        Map result = new Map();
        while (keys.length() > 0) {
            Term key = keys.head();
            Term value = map.get(key);
            if (value != null) {
                result.set(key, value);
            }
            keys = keys.tail();
        }
        return result;
    }

}
