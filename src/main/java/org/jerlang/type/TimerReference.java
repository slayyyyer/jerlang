package org.jerlang.type;

import org.jerlang.vm.TimerWheel;

/**
 * A timer reference.
 *
 * The reference wraps the wheel's handle rather than a
 * `ScheduledFuture`: every VM timer is owned by the timer wheel, and a
 * reference has to be cancellable and readable after the timer fired.
 */
public class TimerReference extends Reference {

    private TimerWheel.Handle handle;

    public TimerReference(TimerWheel.Handle handle) {
        super();
        this.handle = handle;
    }

    /**
     * erlang:start_timer/3 has to put the reference in the message it sends,
     * but the reference can only be built around the handle the wheel returns.
     * The handle is therefore attached after scheduling; the timer action
     * already holds the reference object, so a very short timer still sends
     * the right one.
     */
    public void setHandle(TimerWheel.Handle handle) {
        this.handle = handle;
    }

    public TimerWheel.Handle handle() {
        return handle;
    }

    /**
     * Milliseconds left on the timer, or zero when it already fired.
     */
    public long remaining() {
        return handle == null ? 0 : handle.remainingMillis();
    }

    public TimerReference toTimerReference() {
        return this;
    }

}
