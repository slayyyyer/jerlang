package org.jerlang.stdlib.maps;

import org.jerlang.erts.erlang.Error;
import org.jerlang.erts.erlang.FunApply;
import org.jerlang.type.Atom;
import org.jerlang.type.List;
import org.jerlang.type.Map;
import org.jerlang.type.Term;
import org.jerlang.type.Tuple;

public class MapsUpdateWith {

    private MapsUpdateWith() {
    }

    public static Term dispatch(List params) {
        switch (params.length()) {
        case 3:
            return update_with_3(params.head(), params.tail().head(),
                params.tail().tail().head().toMap());
        case 4:
            return update_with_4(params.head(), params.tail().head(),
                params.tail().tail().head(), params.tail().tail().tail().head().toMap());
        default:
            throw Error.badarg;
        }
    }

    /**
     * http://www.erlang.org/doc/man/maps.html#update_with-3
     */
    public static Map update_with_3(Term key, Term fun, Map map) {
        Term value = map.get(key);
        if (value == null) {
            throw new Error(Tuple.of(Atom.of("badkey"), key));
        }
        return MapsPut.put_3(key, FunApply.apply(fun, List.of(value)), map);
    }

    /**
     * http://www.erlang.org/doc/man/maps.html#update_with-4
     */
    public static Map update_with_4(Term key, Term fun, Term init, Map map) {
        Term value = map.get(key);
        if (value == null) {
            return MapsPut.put_3(key, init, map);
        }
        return MapsPut.put_3(key, FunApply.apply(fun, List.of(value)), map);
    }

}
