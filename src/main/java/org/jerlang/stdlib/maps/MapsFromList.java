package org.jerlang.stdlib.maps;

import org.jerlang.erts.erlang.Error;
import org.jerlang.type.List;
import org.jerlang.type.Map;
import org.jerlang.type.Term;
import org.jerlang.type.Tuple;

public class MapsFromList {

    private MapsFromList() {
    }

    public static Term dispatch(List params) {
        switch (params.length()) {
        case 1:
            return from_list_1(params.head().toList());
        default:
            throw Error.badarg;
        }
    }

    /**
     * Builds a map from a list of {Key, Value} pairs. Later pairs win.
     *
     * http://www.erlang.org/doc/man/maps.html#from_list-1
     */
    public static Map from_list_1(List pairs) {
        Map map = new Map();
        while (pairs.length() > 0) {
            Tuple pair = pairs.head().toTuple();
            map.set(pair.element(1), pair.element(2));
            pairs = pairs.tail();
        }
        return map;
    }

}
