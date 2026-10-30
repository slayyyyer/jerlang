package org.jerlang.stdlib.maps;

import org.jerlang.erts.erlang.Error;
import org.jerlang.type.Atom;
import org.jerlang.type.List;
import org.jerlang.type.Map;
import org.jerlang.type.Term;
import org.jerlang.type.Tuple;

public class MapsGet {

    private MapsGet() {
    }

    public static Term dispatch(List params) {
        switch (params.length()) {
        case 2:
            return get_2(params.head(), params.tail().head().toMap());
        case 3:
            return get_3(params.head(), params.tail().head().toMap(),
                params.tail().tail().head());
        default:
            throw Error.badarg;
        }
    }

    /**
     * http://www.erlang.org/doc/man/maps.html#get-2
     */
    public static Term get_2(Term key, Map map) {
        Term value = map.get(key);
        if (value == null) {
            throw new Error(Tuple.of(Atom.of("badkey"), key));
        }
        return value;
    }

    /**
     * http://www.erlang.org/doc/man/maps.html#get-3
     */
    public static Term get_3(Term key, Map map, Term defaultValue) {
        Term value = map.get(key);
        return value == null ? defaultValue : value;
    }

}
