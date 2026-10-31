package org.jerlang.stdlib.timer;

import org.jerlang.Process;
import org.jerlang.ProcessRegistry;
import org.jerlang.erts.erlang.Error;
import org.jerlang.type.Atom;
import org.jerlang.type.Integer;
import org.jerlang.type.List;
import org.jerlang.type.PID;
import org.jerlang.type.Reference;
import org.jerlang.type.Term;
import org.jerlang.type.TimerReference;
import org.jerlang.type.Tuple;
import org.jerlang.vm.VirtualMachine;

public class TimerSendAfter {

    private TimerSendAfter() {
    }

    public static Term dispatch(List params) {
        switch (params.length()) {
        case 2:
            Integer time2 = params.head().toInteger();
            params = params.tail();
            Term message2 = params.head();
            return send_after_2(time2, message2);
        case 3:
            Integer time3 = params.head().toInteger();
            params = params.tail();
            PID pid3 = params.head().toPID();
            params = params.tail();
            Term message3 = params.head();
            return send_after_3(time3, pid3, message3);
        default:
            throw Error.badarg;
        }
    }

    /**
     * Same as send_after(Time, self(), Message).
     *
     * http://www.erlang.org/doc/man/timer.html#send_after-2
     */
    public static Term send_after_2(Integer time, Term message) {
        return send_after_3(time, ProcessRegistry.self().pid(), message);
    }

    /**
     * Evaluates Pid ! Message after Time amount of time has elapsed.
     * Returns {ok, {send_local, TRef}}, matching the shape of OTP's timer
     * module, where TRef is the reference erlang:cancel_timer/1 accepts.
     *
     * http://www.erlang.org/doc/man/timer.html#send_after-3
     */
    public static Term send_after_3(Integer time, PID pid, Term message) {
        int millis = time.toInt();
        if (millis < 0) {
            return Tuple.of(Atom.of("error"), Atom.of("badarg"));
        }
        if (millis == 0) {
            // OTP sends at once and reports an instant timer, rather than
            // scheduling a wheel entry that is already due.
            Process target = VirtualMachine.instance().resolve(pid);
            if (target != null) {
                target.send(message);
            }
            return Tuple.of(Atom.of("ok"),
                Tuple.of(Atom.of("instant"), new Reference()));
        }
        TimerReference reference = VirtualMachine.instance().send_after(millis, message, pid);
        return Tuple.of(Atom.of("ok"),
            Tuple.of(Atom.of("send_local"), reference));
    }

}
