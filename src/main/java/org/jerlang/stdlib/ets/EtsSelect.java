package org.jerlang.stdlib.ets;

import static org.jerlang.erts.erlang.Error.badarg;

import org.jerlang.type.Atom;
import org.jerlang.type.List;
import org.jerlang.type.Term;
import org.jerlang.type.Tuple;

public class EtsSelect {

    private EtsSelect() {
    }

    public static Term dispatch(List params) {
        switch (params.length()) {
        case 2:
            return select_2(params.head(), params.tail().head());
        case 3:
            return select_3(params.head(), params.tail().head(), params.tail().tail().head());
        default:
            throw badarg;
        }
    }

    /**
     * Matches the objects in the table against a match specification and
     * returns the body of the first clause that matches each object.
     *
     * http://www.erlang.org/doc/man/ets.html#select-2
     */
    public static Term select_2(Term tab, Term specification) {
        return select_3(tab, specification, Atom.of("infinity"));
    }

    /**
     * http://www.erlang.org/doc/man/ets.html#select-3
     */
    public static Term select_3(Term tab, Term specification, Term limit) {
        Table table = TableRegistry.lookup(tab);
        if (table == null) {
            throw badarg;
        }
        MatchSpec spec = MatchSpec.compile(specification);
        int bound = MatchSpec.limit(limit);
        java.util.List<Term> results = new java.util.ArrayList<>();
        if (bound != 0) {
            for (Tuple object : table.objects()) {
                Term result = spec.select(object);
                if (result == null) {
                    continue;
                }
                results.add(result);
                if (bound > 0 && results.size() >= bound) {
                    break;
                }
            }
        }
        return MatchSpec.listOf(results);
    }

}
