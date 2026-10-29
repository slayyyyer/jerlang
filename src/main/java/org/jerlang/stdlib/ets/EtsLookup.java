package org.jerlang.stdlib.ets;

import static org.jerlang.erts.erlang.Error.badarg;

import org.jerlang.type.List;
import org.jerlang.type.Term;

public class EtsLookup {

    private EtsLookup() {
    }

    public static Term dispatch(List params) {
        switch (params.length()) {
        case 2:
            Term tab = params.head();
            params = params.tail();
            Term key = params.head();
            return lookup_2(tab, key);
        default:
            throw badarg;
        }
    }

    /**
     * Returns a list of all objects with the key Key in the table Tab.
     *
     * A table that does not exist is badarg, not an empty result: an empty
     * list would make a typo in a table identifier look like a missing key.
     *
     * http://www.erlang.org/doc/man/ets.html#lookup-2
     */
    public static List lookup_2(Term tab, Term key) {
        Table table = TableRegistry.lookup(tab);
        if (table == null) {
            throw badarg;
        } else {
            return table.lookup(key);
        }
    }

}
