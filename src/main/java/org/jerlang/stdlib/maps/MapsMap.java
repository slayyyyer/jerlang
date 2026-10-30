package org.jerlang.stdlib.maps;

import org.jerlang.erts.erlang.Error;
import org.jerlang.erts.erlang.FunApply;
import org.jerlang.type.List;
import org.jerlang.type.Map;
import org.jerlang.type.Term;

public class MapsMap {

    private MapsMap() {
    }

    public static Term dispatch(List params) {
        switch (params.length()) {
        case 2:
            Term fun = params.head();
            params = params.tail();
            Map map = params.head().toMap();
            return map_2(fun, map);
        default:
            throw Error.badarg;
        }
    }

    /**
     * Calls Fun(Key, Value) for every element of the map and returns a map
     * with the same keys and the returned values.
     *
     * Previously this returned an empty map, which silently discarded the
     * caller's data; gen_server maps its options through it.
     *
     * http://www.erlang.org/doc/man/maps.html#map-2
     */
    public static Map map_2(Term fun, Map map1) {
        Map map2 = new Map();
        for (Term key : MapsToList.orderedKeys(map1)) {
            map2.set(key, FunApply.apply(fun, List.of(key, map1.get(key))));
        }
        return map2;
    }

}
