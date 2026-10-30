package org.jerlang.stdlib.ets;

import static org.jerlang.erts.erlang.Error.badarg;

import org.jerlang.type.Atom;
import org.jerlang.type.List;
import org.jerlang.type.Term;
import org.jerlang.type.Tuple;

public class EtsMatchObject {

    private EtsMatchObject() {
    }

    public static Term dispatch(List params) {
        switch (params.length()) {
        case 2:
            return match_object_2(params.head(), params.tail().head());
        case 3:
            return match_object_3(params.head(), params.tail().head(),
                params.tail().tail().head());
        default:
            throw badarg;
        }
    }

    /**
     * Returns the objects that match the pattern, rather than the values the
     * pattern binds.
     *
     * http://www.erlang.org/doc/man/ets.html#match_object-2
     */
    public static Term match_object_2(Term tab, Term pattern) {
        return match_object_3(tab, pattern, Atom.of("infinity"));
    }

    /**
     * http://www.erlang.org/doc/man/ets.html#match_object-3
     */
    public static Term match_object_3(Term tab, Term pattern, Term limit) {
        Table table = TableRegistry.lookup(tab);
        if (table == null) {
            throw badarg;
        }
        MatchSpec spec = MatchSpec.forPattern(pattern);
        int bound = MatchSpec.limit(limit);
        java.util.List<Term> results = new java.util.ArrayList<>();
        if (bound != 0) {
            for (Tuple object : table.objects()) {
                if (!spec.matches(object)) {
                    continue;
                }
                results.add(new Tuple(object));
                if (bound > 0 && results.size() >= bound) {
                    break;
                }
            }
        }
        return MatchSpec.listOf(results);
    }

}
