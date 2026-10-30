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
     * The keys of a map in term order, which is the order a small map
     * iterates in OTP. The built-in map functions that have to visit every
     * element use this so their behaviour is deterministic.
     */
    public static java.util.List<Term> orderedKeys(Map map) {
        java.util.List<Term> keys = new ArrayList<>();
        List remaining = map.keys();
        while (remaining.length() > 0) {
            keys.add(remaining.head());
            remaining = remaining.tail();
        }
        keys.sort(TermOrder::compare);
        return keys;
    }

    /**
     * The key-value pairs of a map, in key order (term order), which is the
     * order a small map iterates in OTP.
     *
     * http://www.erlang.org/doc/man/maps.html#to_list-1
     */
    public static List to_list_1(Map map) {
        java.util.List<Term> ordered = orderedKeys(map);
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
