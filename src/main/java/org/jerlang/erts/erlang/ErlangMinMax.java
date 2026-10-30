package org.jerlang.erts.erlang;

import org.jerlang.type.Number;
import org.jerlang.type.Term;
import org.jerlang.type.TermOrder;

/**
 * The comparison erlang:min/2 and erlang:max/2 are built on. Both are Python
 * style "return one of the arguments", not a new term.
 */
final class ErlangMinMax {

    private ErlangMinMax() {
    }

    static Term min(Term a, Term b) {
        numbers(a, b);
        return TermOrder.compare(a, b) <= 0 ? a : b;
    }

    static Term max(Term a, Term b) {
        numbers(a, b);
        return TermOrder.compare(a, b) >= 0 ? a : b;
    }

    private static void numbers(Term a, Term b) {
        if (!(a instanceof Number) || !(b instanceof Number)) {
            throw Error.badarg;
        }
    }

}
