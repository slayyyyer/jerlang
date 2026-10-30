package org.jerlang.stdlib.ets;

import static org.jerlang.erts.erlang.Error.badarg;

import org.jerlang.type.Atom;
import org.jerlang.type.List;
import org.jerlang.type.Term;
import org.jerlang.type.Tuple;

public class EtsMatch {

    private EtsMatch() {
    }

    public static Term dispatch(List params) {
        switch (params.length()) {
        case 2:
            return match_2(params.head(), params.tail().head());
        case 3:
            return match_3(params.head(), params.tail().head(), params.tail().tail().head());
        default:
            throw badarg;
        }
    }

    /**
     * Matches the objects in the table against the pattern and returns the
     * values bound to the pattern variables, one list per matching object.
     *
     * http://www.erlang.org/doc/man/ets.html#match-2
     */
    public static Term match_2(Term tab, Term pattern) {
        return match_3(tab, pattern, Atom.of("infinity"));
    }

    /**
     * match/3 stops after Limit results.
     *
     * http://www.erlang.org/doc/man/ets.html#match-3
     */
    public static Term match_3(Term tab, Term pattern, Term limit) {
        Table table = TableRegistry.lookup(tab);
        if (table == null) {
            throw badarg;
        }
        MatchSpec spec = MatchSpec.forPattern(pattern);
        int bound = MatchSpec.limit(limit);
        java.util.List<Term> results = new java.util.ArrayList<>();
        if (bound != 0) {
            for (Tuple object : table.objects()) {
                Term variables = spec.variables(object);
                if (variables == null) {
                    continue;
                }
                results.add(variables);
                if (bound > 0 && results.size() >= bound) {
                    break;
                }
            }
        }
        return MatchSpec.listOf(results);
    }

}
