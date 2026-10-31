package org.jerlang.stdlib;

import org.jerlang.stdlib.timer.TimerCancel;
import org.jerlang.stdlib.timer.TimerSendAfter;
import org.jerlang.type.Integer;
import org.jerlang.type.PID;
import org.jerlang.type.Term;

/**
 * = timer
 *
 * Timer Functions
 *
 * http://www.erlang.org/doc/man/timer.html
 */
public class Timer {

    public static Term cancel(Term timer) {
        return TimerCancel.cancel_1(timer);
    }

    public static Term send_after(Integer time, Term message) {
        return TimerSendAfter.send_after_2(time, message);
    }

    public static Term send_after(Integer time, PID pid, Term message) {
        return TimerSendAfter.send_after_3(time, pid, message);
    }

}
