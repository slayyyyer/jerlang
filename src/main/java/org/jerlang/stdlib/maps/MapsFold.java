package org.jerlang.stdlib.maps;

import org.jerlang.erts.erlang.Error;
import org.jerlang.erts.erlang.FunApply;
import org.jerlang.type.List;
import org.jerlang.type.Map;
import org.jerlang.type.Term;

public class MapsFold {

    private MapsFold() {
    }

    public static Term dispatch(List params) {
        switch (params.length()) {
        case 3:
            return fold_3(params.head(), params.tail().head(), params.tail().tail().head().toMap());
        default:
            throw Error.badarg;
        }
    }

    /**
     * Calls Fun(Key, Value, Acc) for every element of the map, in key order,
     * and returns the final accumulator.
     *
     * http://www.erlang.org/doc/man/maps.html#fold-3
     */
    public static Term fold_3(Term fun, Term init, Map map) {
        Term acc = init;
        for (Term key : MapsToList.orderedKeys(map)) {
            acc = FunApply.apply(fun, List.of(key, map.get(key), acc));
        }
        return acc;
    }

}
