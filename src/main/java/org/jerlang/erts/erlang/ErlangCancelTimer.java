package org.jerlang.erts.erlang;

import org.jerlang.type.Boolean;
import org.jerlang.type.Integer;
import org.jerlang.type.List;
import org.jerlang.type.Term;
import org.jerlang.type.TimerReference;

/**
 * erlang:cancel_timer/1,2 and erlang:read_timer/1.
 *
 * gen_server cancels the timer references of a message it handled, so a
 * gen_server without these can answer one call and then dies with badarg
 * when the next message arrives.
 *
 * http://www.erlang.org/doc/man/erlang.html#cancel_timer-1
 * http://www.erlang.org/doc/man/erlang.html#read_timer-1
 */
public class ErlangCancelTimer {

    private ErlangCancelTimer() {
    }

    public static Term dispatch(List params) {
        switch (params.length()) {
        case 1:
            return cancel_timer_1(params.head());
        case 2:
            return cancel_timer_2(params.head(), params.tail().head().toList());
        default:
            throw Error.badarg;
        }
    }

    /**
     * Cancels the timer and returns the remaining time in milliseconds, or
     * false when it had already fired.
     */
    public static Term cancel_timer_1(Term timer) {
        TimerReference reference = timer(timer);
        if (!reference.future().cancel(false)) {
            return Boolean.am_false;
        }
        return remaining(reference);
    }

    /**
     * The options are `{async, Bool}` and `{info, Bool}`. An asynchronous
     * cancel, or one that asks for no message, answers `ok`; otherwise the
     * remaining time (or false) is returned, like cancel_timer/1.
     *
     * gen_server cancels a handled timer with
     * `erlang:cancel_timer(TRef, [{async, true}, {info, false}])` and matches
     * the answer against `ok`.
     */
    public static Term cancel_timer_2(Term timer, List options) {
        TimerReference reference = timer(timer);
        boolean asynchronous = false;
        boolean info = true;
        while (options.length() > 0) {
            Term option = options.head();
            if (option instanceof org.jerlang.type.Tuple
                && option.toTuple().arity() == 2) {
                String name = option.toTuple().element(1).toString();
                boolean value = option.toTuple().element(2).equals(Boolean.am_true);
                if (name.equals("async")) {
                    asynchronous = value;
                } else if (name.equals("info")) {
                    info = value;
                }
            }
            options = options.tail();
        }
        boolean cancelled = reference.future().cancel(false);
        if (asynchronous || !info) {
            return org.jerlang.type.Atom.of("ok");
        }
        return cancelled ? remaining(reference) : Boolean.am_false;
    }

    /**
     * The remaining time in milliseconds, or false when the timer is not
     * running any more.
     */
    public static Term read_timer_1(Term timer) {
        TimerReference reference = timer(timer);
        if (reference.future().isDone()) {
            return Boolean.am_false;
        }
        return remaining(reference);
    }

    private static TimerReference timer(Term timer) {
        if (!(timer instanceof TimerReference)) {
            if (java.lang.Boolean.getBoolean("jerlang.debug.errors")) {
                System.err.println("CANCEL_TIMER arg class=" + timer.getClass().getName()
                    + " value=" + timer);
            }
            throw Error.badarg;
        }
        return (TimerReference) timer;
    }

    private static Integer remaining(TimerReference reference) {
        long left = reference.deadline() - System.currentTimeMillis();
        return Integer.of((int) Math.max(0, left));
    }

}
