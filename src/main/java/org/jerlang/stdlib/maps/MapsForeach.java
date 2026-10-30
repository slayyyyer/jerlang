package org.jerlang.stdlib.maps;

import org.jerlang.erts.erlang.Error;
import org.jerlang.erts.erlang.FunApply;
import org.jerlang.type.Atom;
import org.jerlang.type.List;
import org.jerlang.type.Map;
import org.jerlang.type.Term;

public class MapsForeach {

    private static final Atom OK = Atom.of("ok");

    private MapsForeach() {
    }

    public static Term dispatch(List params) {
        switch (params.length()) {
        case 2:
            return foreach_2(params.head(), params.tail().head().toMap());
        default:
            throw Error.badarg;
        }
    }

    /**
     * Calls Fun(Key, Value) for every element of the map.
     *
     * http://www.erlang.org/doc/man/maps.html#foreach-2
     */
    public static Term foreach_2(Term fun, Map map) {
        for (Term key : MapsToList.orderedKeys(map)) {
            FunApply.apply(fun, List.of(key, map.get(key)));
        }
        return OK;
    }

}
