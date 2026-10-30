package org.jerlang;

import java.util.Objects;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.jerlang.erts.erlang.Error;
import org.jerlang.type.PID;
import org.jerlang.type.PidOrPortId;
import org.jerlang.type.PortID;
import org.jerlang.vm.Scheduler;

/**
 * Base class for processes and ports.
 */
public abstract class ProcessOrPort {

    private PidOrPortId id;
    private ProcessPriority priority = ProcessPriority.NORMAL;
    private volatile ProcessState state = ProcessState.RUNNABLE;
    private Scheduler scheduler = null;

    /**
     * Counted down when the scheduler finishes the process, so a caller on
     * another thread can wait for the result instead of polling for it.
     */
    private final CountDownLatch terminated = new CountDownLatch(1);

    public ProcessOrPort(PidOrPortId id) {
        this.id = id;
    }

    public abstract void execute();

    public PidOrPortId id() {
        return id;
    }

    public PID pid() {
        return id.toPID();
    }

    public PortID portId() {
        return id.toPortID();
    }

    public ProcessPriority priority() {
        return priority;
    }

    public void setPriority(ProcessPriority priority) {
        this.priority = Objects.requireNonNull(priority);
    }

    public void setScheduler(Scheduler scheduler) {
        this.scheduler = scheduler;
    }

    public void setState(ProcessState state) {
        this.state = state;
    }

    /**
     * Announce that this process is finished and its result is final.
     *
     * This is deliberately not done from {@link #setState}: a call that runs
     * on the same process (apply, call_ext) reaches a return while the caller
     * still has instructions to run, and sets EXITING long before the result
     * is in X0. Only the scheduler knows when the process is really done, so
     * it is the one that calls this.
     */
    public void markTerminated() {
        terminated.countDown();
    }

    /**
     * Block until the scheduler has finished the process, or the timeout
     * expires.
     *
     * Returns immediately when the process finished before the call. A caller
     * waiting for another thread to finish a process is not a cancellation
     * point, so an interrupt does not abandon the wait.
     *
     * @return true if the process has terminated
     */
    public boolean awaitExit(long timeoutMillis) {
        try {
            return terminated.await(timeoutMillis, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            return state == ProcessState.EXITING;
        }
    }

    public Scheduler scheduler() {
        return scheduler;
    }

    public ProcessState state() {
        return state;
    }

    public Port toPort() {
        throw new Error("can not convert to port: " + this);
    }

    public Process toProcess() {
        throw new Error("can not convert to process: " + this);
    }

}
