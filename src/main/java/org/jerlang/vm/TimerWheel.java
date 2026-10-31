package org.jerlang.vm;

import java.util.ArrayList;
import java.util.List;

/**
 * A single-threaded hashed timer wheel.
 *
 * Every VM timer goes through this one wheel: `receive ... after`,
 * `erlang:send_after`, `erlang:start_timer` and `timer:send_after`. Before
 * this, each of them created a task on a scheduled thread pool, and a
 * `receive ... after` had its own separate executor. A timer wheel gives one
 * wakeup path, one clock and one place where a cancellation can be applied.
 *
 * The wheel has 512 one-millisecond slots. A task is placed in the slot of
 * its deadline tick. A long timer shares a slot with shorter timers, so a
 * slot can be visited before the long timer is due; the task is then put back
 * and looked at again one wrap later. The action runs outside the wheel lock,
 * so a timer callback can schedule or cancel another timer without
 * deadlocking.
 */
public final class TimerWheel {

    private static final long TICK_NANOS = 1_000_000L;
    private static final int SLOTS = 512;

    /**
     * The longest the wheel sleeps when it has pending timers. A new or
     * cancelled timer can move the next deadline, and the wheel has to notice
     * without waiting for the whole slot wrap.
     */
    private static final long MAX_WAIT_NANOS = 100_000_000L;

    /**
     * A scheduled timer. A handle stays usable after it fired: cancel then
     * returns false and isDone returns true, which is what
     * `erlang:cancel_timer/1` and `read_timer/1` report.
     */
    public interface Handle {

        boolean cancel();

        boolean isDone();

        long remainingMillis();

    }

    private final class Task implements Handle {

        private final Runnable action;
        private final long deadlineNanos;
        private final long deadlineTick;
        private int slotIndex;
        private boolean cancelled;
        private boolean done;

        private Task(Runnable action, long deadlineNanos) {
            this.action = action;
            this.deadlineNanos = deadlineNanos;
            this.deadlineTick = (deadlineNanos + TICK_NANOS - 1) / TICK_NANOS;
        }

        @Override
        public boolean cancel() {
            synchronized (lock) {
                if (done || cancelled) {
                    return false;
                }
                cancelled = true;
                pending--;
                slots[slotIndex].remove(this);
                return true;
            }
        }

        @Override
        public boolean isDone() {
            synchronized (lock) {
                return done || cancelled;
            }
        }

        @Override
        public long remainingMillis() {
            long left = deadlineNanos - System.nanoTime();
            return Math.max(0, left / TICK_NANOS);
        }

    }

    private final Object lock = new Object();
    private final List<Task>[] slots;
    private final Thread thread;

    private long currentTick;
    private long nextDeadlineTick = Long.MAX_VALUE;
    private int pending = 0;
    private volatile boolean stopped = false;

    @SuppressWarnings("unchecked")
    public TimerWheel() {
        slots = new List[SLOTS];
        for (int index = 0; index < SLOTS; index++) {
            slots[index] = new ArrayList<>();
        }
        currentTick = System.nanoTime() / TICK_NANOS;
        thread = new Thread(this::run, "jerlang-timer-wheel");
        thread.setDaemon(true);
        thread.start();
    }

    /**
     * Schedule an action after the given number of milliseconds. A negative
     * delay is treated as zero, as OTP does for a timer that is already due.
     */
    public Handle schedule(Runnable action, long delayMillis) {
        long now = System.nanoTime();
        long deadline = now + Math.max(0, delayMillis) * TICK_NANOS;
        Task task = new Task(action, deadline);
        synchronized (lock) {
            if (stopped) {
                throw new IllegalStateException("timer wheel is stopped");
            }
            long nowTick = now / TICK_NANOS;
            if (nowTick > currentTick) {
                currentTick = nowTick;
            }
            long tick = task.deadlineTick;
            if (tick <= currentTick) {
                tick = currentTick + 1;
            }
            task.slotIndex = slot(tick);
            slots[task.slotIndex].add(task);
            pending++;
            if (tick < nextDeadlineTick) {
                nextDeadlineTick = tick;
                lock.notifyAll();
            }
        }
        return task;
    }

    /**
     * Stop the wheel and release its thread. Timers that have not fired do
     * not run; the VM is shutting down, so their destinations are going away
     * as well.
     */
    public void stop() {
        synchronized (lock) {
            if (stopped) {
                return;
            }
            stopped = true;
            lock.notifyAll();
        }
    }

    public void awaitStop() {
        try {
            thread.join(1000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private void run() {
        while (true) {
            List<Task> due = new ArrayList<>();
            synchronized (lock) {
                if (stopped) {
                    return;
                }
                long nowTick = System.nanoTime() / TICK_NANOS;
                if (nextDeadlineTick != Long.MAX_VALUE && nowTick >= nextDeadlineTick) {
                    // A delayed timer thread can be several ticks behind.
                    // Visit every missed slot, but do not spin for an
                    // unbounded amount of time if the process was frozen.
                    long target = Math.min(nowTick, currentTick + SLOTS);
                    while (currentTick < target) {
                        currentTick++;
                        collect(slot(currentTick), due);
                    }
                    recomputeNextDeadline();
                }
                if (due.isEmpty()) {
                    if (pending == 0) {
                        nextDeadlineTick = Long.MAX_VALUE;
                        waitForWork();
                    } else {
                        long wait = nextDeadlineTick == Long.MAX_VALUE ? MAX_WAIT_NANOS
                            : (nextDeadlineTick - currentTick) * TICK_NANOS;
                        if (wait > MAX_WAIT_NANOS) {
                            wait = MAX_WAIT_NANOS;
                        }
                        if (wait > 0) {
                            waitNanos(wait);
                        }
                    }
                    continue;
                }
            }
            for (Task task : due) {
                try {
                    task.action.run();
                } catch (Throwable failure) {
                    // A timer callback is not allowed to kill the wheel.
                    System.err.println("timer callback failed: " + failure);
                    failure.printStackTrace(System.err);
                }
            }
        }
    }

    private void collect(int index, List<Task> due) {
        List<Task> slot = slots[index];
        if (slot.isEmpty()) {
            return;
        }
        List<Task> keep = new ArrayList<>(slot.size());
        for (Task task : slot) {
            if (task.cancelled || task.done) {
                continue;
            }
            if (task.deadlineTick <= currentTick) {
                task.done = true;
                pending--;
                due.add(task);
            } else {
                keep.add(task);
            }
        }
        slot.clear();
        slot.addAll(keep);
    }

    /**
     * Find the earliest deadline still in the wheel. This runs only after a
     * slot was visited, not on every tick, so a far-away timer costs one scan
     * when it is scheduled and nothing until it is due.
     */
    private void recomputeNextDeadline() {
        long next = Long.MAX_VALUE;
        for (List<Task> slot : slots) {
            for (Task task : slot) {
                if (!task.cancelled && !task.done && task.deadlineTick < next) {
                    next = task.deadlineTick;
                }
            }
        }
        nextDeadlineTick = next;
    }

    private void waitForWork() {
        try {
            lock.wait();
        } catch (InterruptedException e) {
            if (stopped) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private void waitNanos(long nanos) {
        long millis = nanos / TICK_NANOS;
        int rest = (int) (nanos % TICK_NANOS);
        try {
            lock.wait(millis, rest);
        } catch (InterruptedException e) {
            if (stopped) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private int slot(long tick) {
        return (int) Math.floorMod(tick, SLOTS);
    }

}
