package org.jerlang.stdlib.lists;

import org.jerlang.erts.erlang.Error;
import org.jerlang.type.Boolean;
import org.jerlang.type.Integer;
import org.jerlang.type.List;
import org.jerlang.type.Term;

public class ListsKeymember {

    private ListsKeymember() {
    }

    public static Term dispatch(List params) {
        switch (params.length()) {
        case 3:
            Term key = params.head();
            params = params.tail();
            Integer position = params.head().toInteger();
            List tuples = params.tail().head().toList();
            return keymember_3(key, position, tuples);
        default:
            throw Error.badarg;
        }
    }

    /**
     * True when some tuple in `tuples` has `key` at `position`.
     *
     * http://www.erlang.org/doc/man/lists.html#keymember-3
     */
    public static Term keymember_3(Term key, Integer position, List tuples) {
        return ListsKeyfind.search(key, position, tuples) == null
            ? Boolean.am_false : Boolean.am_true;
    }

}
