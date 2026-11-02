package org.jerlang.stdlib.ets;

import static org.jerlang.erts.erlang.Error.badarg;

import org.jerlang.type.Boolean;
import org.jerlang.type.List;
import org.jerlang.type.Term;
import org.jerlang.type.Tuple;

public class EtsMatchDelete {

    private EtsMatchDelete() {
    }

    public static Term dispatch(List params) {
        if (params.length() != 2) {
            throw badarg;
        }
        return match_delete_2(params.head(), params.tail().head());
    }

    /** Delete every object that matches the pattern and return true. */
    public static Term match_delete_2(Term tab, Term pattern) {
        Table table = TableRegistry.lookup(tab);
        if (table == null) {
            throw badarg;
        }
        MatchSpec spec = MatchSpec.forPattern(pattern);
        for (Tuple object : table.objects()) {
            if (spec.matches(object)) {
                table.remove(object);
            }
        }
        return Boolean.am_true;
    }

}
