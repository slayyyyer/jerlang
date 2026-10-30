package org.jerlang.stdlib.maps;

import org.jerlang.erts.erlang.Error;
import org.jerlang.type.List;
import org.jerlang.type.Map;
import org.jerlang.type.Term;

public class MapsMerge {

    private MapsMerge() {
    }

    public static Term dispatch(List params) {
        switch (params.length()) {
        case 2:
            Map map1 = params.head().toMap();
            params = params.tail();
            Map map2 = params.head().toMap();
            return merge_2(map1, map2);
        default:
            throw Error.badarg;
        }
    }

    /**
     * Merges two maps into a single map. If a key exists in both maps, the
     * value from `map2` is used.
     *
     * Both maps are left unchanged: the result is a new map.
     *
     * http://www.erlang.org/doc/man/maps.html#merge-2
     */
    public static Map merge_2(Map map1, Map map2) {
        Map merged = new Map(map1);
        List keys = map2.keys();
        while (keys.length() > 0) {
            Term key = keys.head();
            merged.set(key, map2.get(key));
            keys = keys.tail();
        }
        return merged;
    }

}
