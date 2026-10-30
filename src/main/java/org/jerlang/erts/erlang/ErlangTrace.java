package org.jerlang.erts.erlang;

import org.jerlang.Process;
import org.jerlang.ProcessOrPort;
import org.jerlang.ProcessRegistry;
import org.jerlang.ProcessSignals;
import org.jerlang.Tracing;
import org.jerlang.type.Atom;
import org.jerlang.type.Boolean;
import org.jerlang.type.Integer;
import org.jerlang.type.List;
import org.jerlang.type.PID;
import org.jerlang.type.Term;
import org.jerlang.type.Tuple;

/**
 * erlang:trace/3.
 *
 * Target can be a pid, `all`, `existing` or `new`. `all` includes processes
 * created later; `existing` does not. The return value is the number of
 * processes the flags were applied to, as in OTP.
 */
public class ErlangTrace {

    private ErlangTrace() {
    }

    public static Term dispatch(List params) {
        if (params.length() != 3) {
            throw Error.badarg;
        }
        return trace_3(params.head(), params.tail().head(), params.tail().tail().head().toList());
    }

    public static Term trace_3(Term target, Term how, List flags) {
        boolean enable;
        if (how.equals(Boolean.am_true)) {
            enable = true;
        } else if (how.equals(Boolean.am_false)) {
            enable = false;
        } else {
            throw Error.badarg;
        }
        Process caller = ProcessSignals.self();
        Tracing.Settings settings = Tracing.parse(flags, caller.pid());

        if (target instanceof PID) {
            Process process = resolve((PID) target);
            if (process == null) {
                return Integer.ZERO;
            }
            Tracing.apply(process, enable, settings);
            return Integer.ONE;
        }
        if (target instanceof Atom) {
            String name = target.toString();
            if (name.equals("all") || name.equals("existing")) {
                int count = 0;
                for (ProcessOrPort process : ProcessRegistry.all()) {
                    if (process instanceof Process) {
                        Tracing.apply((Process) process, enable, settings);
                        count++;
                    }
                }
                if (name.equals("all")) {
                    Tracing.traceNew(enable, settings);
                }
                return Integer.of(count);
            }
            if (name.equals("new")) {
                Tracing.traceNew(enable, settings);
                return Integer.ONE;
            }
        }
        throw Error.badarg;
    }

    private static Process resolve(PID pid) {
        ProcessOrPort process = ProcessRegistry.resolve(pid);
        if (!(process instanceof Process) || ((Process) process).isTerminated()) {
            return null;
        }
        return (Process) process;
    }

    static Term tuple(Term... terms) {
        return Tuple.of(terms);
    }

}
