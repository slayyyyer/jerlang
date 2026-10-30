package org.jerlang.erts.erlang;

import org.jerlang.type.Atom;
import org.jerlang.type.List;
import org.jerlang.type.Map;
import org.jerlang.type.Term;
import org.jerlang.type.Tuple;

/**
 * erlang:map_get/2 is a BIF. OTP's own modules call it directly (the
 * supervisor looks children up by id with it), so the runtime supplies it
 * instead of the nif_error stub in erlang.beam.
 *
 * http://www.erlang.org/doc/man/erlang.html#map_get-2
 */
public class ErlangMapGet {

    private ErlangMapGet() {
    }

    public static Term dispatch(List params) {
        switch (params.length()) {
        case 2:
            return map_get_2(params.head(), params.tail().head().toMap());
        default:
            throw Error.badarg;
        }
    }

    public static Term map_get_2(Term key, Map map) {
        Term value = map.get(key);
        if (value == null) {
            throw new Error(Tuple.of(Atom.of("badkey"), key));
        }
        return value;
    }

}
