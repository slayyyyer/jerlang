package org.jerlang.stdlib.maps;

import java.util.ArrayList;

import org.jerlang.type.List;
import org.jerlang.type.Map;
import org.jerlang.type.Term;
import org.jerlang.type.TermOrder;
import org.jerlang.type.Tuple;

public class MapsToList {

    private MapsToList() {
    }

    /**
     * The key-value pairs of a map, in key order (term order), which is the
     * order a small map iterates in OTP.
     *
     * http://www.erlang.org/doc/man/maps.html#to_list-1
     */
    public static List to_list_1(Map map) {
        List keys = map.keys();
        java.util.List<Term> ordered = new ArrayList<>();
        while (keys.length() > 0) {
            ordered.add(keys.head());
            keys = keys.tail();
        }
        ordered.sort(TermOrder::compare);
        List result = List.nil;
        for (int index = ordered.size() - 1; index >= 0; index--) {
            Term key = ordered.get(index);
            result = new List(Tuple.of(key, map.get(key)), result);
        }
        return result;
    }

    public static Term dispatch(List params) {
        switch (params.length()) {
        case 1:
            return to_list_1(params.head().toMap());
        default:
            throw org.jerlang.erts.erlang.Error.badarg;
        }
    }

}
