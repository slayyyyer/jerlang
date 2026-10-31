package org.jerlang.stdlib.timer;

import org.jerlang.erts.erlang.Error;
import org.jerlang.type.Atom;
import org.jerlang.type.List;
import org.jerlang.type.Reference;
import org.jerlang.type.Term;
import org.jerlang.type.TimerReference;
import org.jerlang.type.Tuple;
import org.jerlang.vm.VirtualMachine;

public class TimerCancel {

    private TimerCancel() {
    }

    public static Term dispatch(List params) {
        switch (params.length()) {
        case 1:
            return cancel_1(params.head());
        default:
            throw Error.badarg;
        }
    }

    /**
     * Cancels a previously requested timeout.
     * TRef is a unique timer reference returned by the timer
     * function in question.
     * Returns {ok, cancel}, or {error, Reason} when TRef is
     * not a timer reference.
     *
     * http://www.erlang.org/doc/man/timer.html#cancel-1
     */
    public static Term cancel_1(Term timer) {
        if (timer instanceof TimerReference) {
            return VirtualMachine.instance().cancel((TimerReference) timer);
        }
        if (timer instanceof Tuple && ((Tuple) timer).arity() == 2) {
            Tuple wrapper = (Tuple) timer;
            Term tag = wrapper.element(1);
            Term reference = wrapper.element(2);
            if (Atom.of("send_local").equals(tag)
                && reference instanceof TimerReference) {
                return VirtualMachine.instance().cancel((TimerReference) reference);
            }
            // A timer that already fired at time zero has nothing to cancel.
            if (Atom.of("instant").equals(tag) && reference instanceof Reference) {
                return Tuple.of(Atom.of("ok"), Atom.of("cancel"));
            }
        }
        return Tuple.of(Atom.of("error"), Atom.of("badarg"));
    }

}
