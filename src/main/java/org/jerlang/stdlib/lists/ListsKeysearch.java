package org.jerlang.stdlib.lists;

import org.jerlang.erts.erlang.Error;
import org.jerlang.type.Atom;
import org.jerlang.type.Boolean;
import org.jerlang.type.Integer;
import org.jerlang.type.List;
import org.jerlang.type.Term;
import org.jerlang.type.Tuple;

public class ListsKeysearch {

    private ListsKeysearch() {
    }

    public static Term dispatch(List params) {
        switch (params.length()) {
        case 3:
            Term key = params.head();
            params = params.tail();
            Integer position = params.head().toInteger();
            List tuples = params.tail().head().toList();
            return keysearch_3(key, position, tuples);
        default:
            throw Error.badarg;
        }
    }

    /**
     * Returns {value, Tuple} for the first matching tuple, or false.
     * This is the older form of keyfind/3 and is also a BIF.
     *
     * http://www.erlang.org/doc/man/lists.html#keysearch-3
     */
    public static Term keysearch_3(Term key, Integer position, List tuples) {
        Tuple tuple = ListsKeyfind.search(key, position, tuples);
        return tuple == null ? Boolean.am_false : Tuple.of(Atom.of("value"), tuple);
    }

}
