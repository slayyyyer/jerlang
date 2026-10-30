package org.jerlang.stdlib.maps;

import org.jerlang.erts.erlang.Error;
import org.jerlang.type.List;
import org.jerlang.type.Map;
import org.jerlang.type.Term;

public class MapsRemove {

    private MapsRemove() {
    }

    public static Term dispatch(List params) {
        switch (params.length()) {
        case 2:
            return remove_2(params.head(), params.tail().head().toMap());
        default:
            throw Error.badarg;
        }
    }

    /**
     * Removes Key from a copy of Map. A key that is not there is not an
     * error.
     *
     * http://www.erlang.org/doc/man/maps.html#remove-2
     */
    public static Map remove_2(Term key, Map map) {
        Map result = new Map(map);
        result.remove(key);
        return result;
    }

}
