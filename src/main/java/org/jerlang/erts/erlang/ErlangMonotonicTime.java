package org.jerlang.erts.erlang;

import org.jerlang.type.Atom;
import org.jerlang.type.Integer;
import org.jerlang.type.List;
import org.jerlang.type.Term;
import org.jerlang.type.Tuple;

/**
 * erlang:monotonic_time/0,1 returns a monotonically increasing time, which
 * only means anything when compared with another value from the same unit.
 * OTP uses it to measure how much of a timeout is left, for example in
 * proc_lib:stop/3, so any monotonic nanosecond clock works.
 *
 * http://www.erlang.org/doc/man/erlang.html#monotonic_time-0
 */
public class ErlangMonotonicTime {

    private ErlangMonotonicTime() {
    }

    public static Term dispatch(List params) {
        switch (params.length()) {
        case 0:
            return monotonic_time_0();
        case 1:
            return monotonic_time_1(params.head());
        default:
            throw Error.badarg;
        }
    }

    public static Term monotonic_time_0() {
        return monotonic_time_1(Atom.of("native"));
    }

    public static Term monotonic_time_1(Term unit) {
        long nanoseconds = System.nanoTime();
        long divisor;
        if (unit instanceof Tuple) {
            Tuple description = (Tuple) unit;
            if (description.arity() != 2 || !Atom.of("unit").equals(description.element(1))) {
                throw Error.badarg;
            }
            divisor = description.element(2).toInteger().toLong();
            if (divisor <= 0) {
                throw Error.badarg;
            }
        } else {
            if (!(unit instanceof Atom)) {
                throw Error.badarg;
            }
            switch (unit.toString()) {
            case "second":
                divisor = 1000000000L;
                break;
            case "millisecond":
                divisor = 1000000L;
                break;
            case "microsecond":
                divisor = 1000L;
                break;
            case "nanosecond":
            case "native":
            case "perf_counter":
                divisor = 1L;
                break;
            default:
                throw Error.badarg;
            }
        }
        return new Integer(java.math.BigInteger.valueOf(nanoseconds / divisor));
    }

}
