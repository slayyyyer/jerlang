package org.jerlang.erts.erlang;

import org.jerlang.type.List;
import org.jerlang.type.Term;

/**
 * erlang:read_timer/1.
 *
 * The module exporter derives the implementation class from the function
 * name, so read_timer needs its own class even though the logic lives next to
 * cancel_timer.
 *
 * http://www.erlang.org/doc/man/erlang.html#read_timer-1
 */
public class ErlangReadTimer {

    private ErlangReadTimer() {
    }

    public static Term dispatch(List params) {
        switch (params.length()) {
        case 1:
            return read_timer_1(params.head());
        default:
            throw Error.badarg;
        }
    }

    public static Term read_timer_1(Term timer) {
        return ErlangCancelTimer.read_timer_1(timer);
    }

}
