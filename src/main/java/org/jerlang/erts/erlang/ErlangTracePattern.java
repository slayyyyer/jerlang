package org.jerlang.erts.erlang;

import org.jerlang.Tracing;
import org.jerlang.type.Atom;
import org.jerlang.type.Integer;
import org.jerlang.type.List;
import org.jerlang.type.Term;
import org.jerlang.type.Tuple;

/**
 * erlang:trace_pattern/3.
 *
 * A function, a module or `all` can be instrumented. The match-spec subset is
 * intentionally small: true and a non-empty list enable every call to the
 * function, false and the empty list disable it. The `return_trace` flag adds
 * a return_to event; `local`, `global` and `meta` are accepted so a standard
 * dbg call works.
 */
public class ErlangTracePattern {

    private ErlangTracePattern() {
    }

    public static Term dispatch(List params) {
        if (params.length() != 3) {
            throw Error.badarg;
        }
        return trace_pattern_3(params.head(), params.tail().head(),
            params.tail().tail().head().toList());
    }

    public static Term trace_pattern_3(Term target, Term matchSpec, List flags) {
        if (target instanceof Tuple && ((Tuple) target).arity() == 3) {
            Term module = ((Tuple) target).element(1);
            Term function = ((Tuple) target).element(2);
            Term arity = ((Tuple) target).element(3);
            if (!(module instanceof Atom) || !(function instanceof Atom)
                || !(arity instanceof Integer)) {
                throw Error.badarg;
            }
        } else if (!(target instanceof Atom)) {
            throw Error.badarg;
        }
        Tracing.pattern(target, matchSpec, flags);
        return Integer.ONE;
    }

}
