package org.jerlang.erts.erlang;

import org.jerlang.ProcessRegistry;
import org.jerlang.type.Atom;
import org.jerlang.type.Integer;
import org.jerlang.type.List;
import org.jerlang.type.PID;
import org.jerlang.type.PidOrPortId;
import org.jerlang.type.Term;
import org.jerlang.type.TimerReference;
import org.jerlang.vm.VirtualMachine;

/**
 * Starts a timer that sends {timeout, TRef, Message} to the destination.
 *
 * gen_server uses start_timer/3 for its timeouts (hibernate_after, a
 * {continue, ...} handler, the call timeout of a cast), so the alternative is
 * a server that cannot be started with a timeout option at all.
 *
 * http://www.erlang.org/doc/man/erlang.html#start_timer-3
 */
public class ErlangStartTimer {

    private ErlangStartTimer() {
    }

    public static Term dispatch(List params) {
        switch (params.length()) {
        case 3:
            return start_timer_3(params.head().toInteger(), params.tail().head(),
                params.tail().tail().head());
        case 4:
            // The fourth argument only selects the time unit (millisecond,
            // second, ...); the default is millisecond.
            return start_timer_4(params.head().toInteger(), params.tail().head(),
                params.tail().tail().head(), params.tail().tail().tail().head());
        default:
            throw Error.badarg;
        }
    }

    public static TimerReference start_timer_3(Integer time, Term destination, Term message) {
        return start_timer_4(time, destination, message, Atom.of("millisecond"));
    }

    public static TimerReference start_timer_4(Integer time, Term destination, Term message,
        Term timeUnit) {
        int millis = toMillis(time.toInt(), timeUnit);
        return VirtualMachine.instance().start_timer(millis, message, resolve(destination));
    }

    private static int toMillis(int time, Term unit) {
        if (!(unit instanceof Atom)) {
            throw Error.badarg;
        }
        switch (unit.toString()) {
        case "second":
            return time * 1000;
        case "microsecond":
            return time / 1000;
        case "nanosecond":
            return time / 1000000;
        case "millisecond":
            return time;
        default:
            throw Error.badarg;
        }
    }

    private static PID resolve(Term destination) {
        if (destination instanceof PID) {
            return (PID) destination;
        }
        if (destination instanceof Atom) {
            PidOrPortId id = ProcessRegistry.whereis((Atom) destination);
            if (id instanceof PID) {
                return (PID) id;
            }
        }
        throw Error.badarg;
    }

}
