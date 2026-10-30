package org.jerlang.stdlib.maps;

import org.jerlang.erts.erlang.Error;
import org.jerlang.type.Atom;
import org.jerlang.type.List;
import org.jerlang.type.Map;
import org.jerlang.type.Term;
import org.jerlang.type.Tuple;

public class MapsTake {

    private static final Atom ERROR = Atom.of("error");

    private MapsTake() {
    }

    public static Term dispatch(List params) {
        switch (params.length()) {
        case 2:
            return take_2(params.head(), params.tail().head().toMap());
        default:
            throw Error.badarg;
        }
    }

    /**
     * Removes Key from a copy of Map and returns {Value, NewMap}, or error
     * when the key is not there.
     *
     * http://www.erlang.org/doc/man/maps.html#take-2
     */
    public static Term take_2(Term key, Map map) {
        Term value = map.get(key);
        if (value == null) {
            return ERROR;
        }
        return Tuple.of(value, MapsRemove.remove_2(key, map));
    }

}
