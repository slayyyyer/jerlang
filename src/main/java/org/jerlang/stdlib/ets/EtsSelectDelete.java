package org.jerlang.stdlib.ets;

import static org.jerlang.erts.erlang.Error.badarg;

import org.jerlang.type.Integer;
import org.jerlang.type.List;
import org.jerlang.type.Term;
import org.jerlang.type.Tuple;

public class EtsSelectDelete {

    private EtsSelectDelete() {
    }

    public static Term dispatch(List params) {
        if (params.length() != 2) {
            throw badarg;
        }
        return select_delete_2(params.head(), params.tail().head());
    }

    /** Delete every object selected by the match spec, returning the count. */
    public static Term select_delete_2(Term tab, Term specification) {
        Table table = TableRegistry.lookup(tab);
        if (table == null) {
            throw badarg;
        }
        MatchSpec spec = MatchSpec.compile(specification);
        int deleted = 0;
        for (Tuple object : table.objects()) {
            if (spec.select(object) != null) {
                table.remove(object);
                deleted++;
            }
        }
        return Integer.of(deleted);
    }

}
