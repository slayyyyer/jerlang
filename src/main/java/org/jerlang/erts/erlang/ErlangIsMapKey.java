package org.jerlang.erts.erlang;

import org.jerlang.type.Boolean;
import org.jerlang.type.List;
import org.jerlang.type.Map;
import org.jerlang.type.Term;

/**
 * http://www.erlang.org/doc/man/erlang.html#is_map_key-2
 */
public class ErlangIsMapKey {

    private ErlangIsMapKey() {
    }

    public static Term dispatch(List params) {
        switch (params.length()) {
        case 2:
            return is_map_key_2(params.head(), params.tail().head().toMap());
        default:
            throw Error.badarg;
        }
    }

    public static Term is_map_key_2(Term key, Map map) {
        return Boolean.of(map.is_key(key));
    }

}
