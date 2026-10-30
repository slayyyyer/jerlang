package org.jerlang.type;

import java.util.concurrent.ScheduledFuture;

/**
 * A timer reference.
 */
public class TimerReference extends Reference {

    private final ScheduledFuture<?> future;
    // When the timer is due, in wall-clock milliseconds. erlang:read_timer/1
    // and the remaining time of erlang:cancel_timer/1 need it.
    private final long deadline;

    public TimerReference(ScheduledFuture<?> future) {
        this(future, 0);
    }

    public TimerReference(ScheduledFuture<?> future, long deadline) {
        super();
        this.future = future;
        this.deadline = deadline;
    }

    public ScheduledFuture<?> future() {
        return future;
    }

    public long deadline() {
        return deadline;
    }

    public TimerReference toTimerReference() {
        return this;
    }

}
