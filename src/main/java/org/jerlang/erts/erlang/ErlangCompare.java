package org.jerlang.erts.erlang;

import org.jerlang.type.Atom;
import org.jerlang.type.Boolean;
import org.jerlang.type.Term;
import org.jerlang.type.TermOrder;

/**
 * The comparison operators, implemented once for the BIFs that compiled code
 * calls when a comparison produces a value instead of only guarding.
 *
 * A comparison expression such as `A &lt; B` in a function body compiles to a
 * `bif` instruction with `erlang:'&lt;'/2`, while the same test in a guard
 * compiles to `is_lt` or `is_ge`. Both paths use {@link TermOrder}, so they
 * agree on every term type.
 */
public final class ErlangCompare {

    private ErlangCompare() {
    }

    public static Atom lt(Term a, Term b) {
        return Boolean.of(TermOrder.compare(a, b) < 0);
    }

    public static Atom le(Term a, Term b) {
        return Boolean.of(TermOrder.compare(a, b) <= 0);
    }

    public static Atom gt(Term a, Term b) {
        return Boolean.of(TermOrder.compare(a, b) > 0);
    }

    public static Atom ge(Term a, Term b) {
        return Boolean.of(TermOrder.compare(a, b) >= 0);
    }

    public static Atom eq(Term a, Term b) {
        return Boolean.of(TermOrder.equal(a, b));
    }

    public static Atom ne(Term a, Term b) {
        return Boolean.of(!TermOrder.equal(a, b));
    }

    public static Atom eqExact(Term a, Term b) {
        return Boolean.of(a.equals(b));
    }

    public static Atom neExact(Term a, Term b) {
        return Boolean.of(!a.equals(b));
    }

}
