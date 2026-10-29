package org.jerlang.vm;

import java.util.HashSet;

import org.jerlang.Process;
import org.jerlang.ProcessOrPort;
import org.jerlang.ProcessRegistry;
import org.jerlang.ProcessSignals;
import org.jerlang.ProcessState;
import org.jerlang.type.Atom;
import org.jerlang.type.PidOrPortId;
import org.jerlang.type.Term;

/**
 * References:
 * http://www.erlang.se/euc/08/euc_smp.pdf
 */
public class Scheduler extends Thread {

    private final HashSet<PidOrPortId> ids;

    private final RunQueue<ProcessOrPort> runQueueMax;
    private final RunQueue<ProcessOrPort> runQueueHigh;
    private final RunQueue<ProcessOrPort> runQueueNormal;
    private final RunQueue<ProcessOrPort> runQueueLow;

    /** Guards {@link #workPending}, which carries a wakeup across the gap
     * between finding the queues empty and starting to wait. */
    private final Object workLock = new Object();
    private boolean workPending = false;

    public Scheduler() {
        ids = new HashSet<>();
        runQueueMax = new RunQueue<>();
        runQueueHigh = new RunQueue<>();
        runQueueNormal = new RunQueue<>();
        runQueueLow = new RunQueue<>();
    }

    public ProcessOrPort add(ProcessOrPort p) {
        // A waiting process keeps its id while it is off the run queue, so
        // membership must not prevent a message or timeout from re-queueing
        // it. Only RUNNABLE processes are ever pushed back.
        ids.add(p.id());

        p.setScheduler(this);
        switch (p.priority()) {
        case MAX:
            runQueueMax.push(p);
            break;
        case HIGH:
            runQueueHigh.push(p);
            break;
        case NORMAL:
            runQueueNormal.push(p);
            break;
        case LOW:
            runQueueLow.push(p);
            break;
        }
        // Signal only after the process is on a queue, so a woken scheduler
        // can not poll an empty queue and park again while work sits unseen.
        wakeup();
        return p;
    }

    /**
     * Performing actions according to:
     * https://www.erlang-solutions.com/resources/webinars/understanding-erlang-scheduler
     */
    @Override
    public void run() {
        while (!isInterrupted()) {
            checkIfTimersShouldBeTriggered();
            maybeCheckBalance();
            migrateProcessesAndPorts();
            executeAnyAuxWork();
            maybeCheckIO();
            executeOnePort();
            executeOneProcess();
        }
    }

    private void checkIfTimersShouldBeTriggered() {
    }

    private void maybeCheckBalance() {
    }

    private void migrateProcessesAndPorts() {
    }

    private void executeAnyAuxWork() {
    }

    private void maybeCheckIO() {
    }

    private void executeOnePort() {
    }

    private void executeOneProcess() {
        if (!execute(runQueueMax)) {
            if (!execute(runQueueHigh)) {
                if (!execute(runQueueNormal)) {
                    if (!execute(runQueueLow)) {
                        sleep();
                    }
                }
            }
        }
    }

    private boolean execute(RunQueue<ProcessOrPort> runQueue) {
        ProcessOrPort p = runQueue.poll();
        if (p != null) {
            ProcessRegistry.self(p);
            Process process = p instanceof Process ? (Process) p : null;
            Throwable failure = null;
            try {
                // An exit signal that arrived while the process was off the
                // run queue takes effect before another instruction runs.
                if (process != null && process.hasPendingExit()
                    && p.state() != ProcessState.EXITING) {
                    p.setState(ProcessState.EXITING);
                }
                if (p.state() != ProcessState.EXITING) {
                    p.execute();
                }
            } catch (Throwable t) {
                // An uncaught error terminates the process, but must not
                // terminate the scheduler thread that ran it.
                failure = t;
            } finally {
                ProcessRegistry.self(null);
            }
            if (failure != null) {
                if (process != null) {
                    process.setFailure(failure);
                }
                p.setState(ProcessState.EXITING);
            }
            if (process != null && p.state() == ProcessState.EXITING) {
                // An uncaught exception is reported before the links and
                // monitors hear about it, so the report is not lost when a
                // linked process handles the exit first.
                CrashReport.print(process);
                // The exit reason is the pending signal when there is one,
                // otherwise what the process crashed with or `normal`.
                Term reason = process.pendingExitReason();
                if (reason == null) {
                    reason = process.failure() == null
                        ? Atom.of("normal")
                        : ProcessSignals.reason(process.failure());
                }
                ProcessSignals.terminate(process, reason);
            }
            if (p.state() == ProcessState.EXITING) {
                // Nothing more will run for this process, so a caller waiting
                // on it can read the result now. This has to be here rather
                // than on the state change: EXITING is also reached inside a
                // nested call, before the outer activation has finished.
                p.markTerminated();
            }
            switch (p.state()) {
            case RUNNABLE:
                runQueue.push(p);
                break;
            case WAITING:
                // Keep it known to this scheduler. Process.send or its
                // timer will make it runnable and enqueue it again.
                break;
            default:
                ids.remove(p.pid());
                break;
            }
            return true;
        }
        return false;
    }

    /**
     * Wake a scheduler that is waiting for work. Called for every enqueue, so
     * a newly runnable process does not wait for the next poll.
     */
    private void wakeup() {
        synchronized (workLock) {
            workPending = true;
            workLock.notify();
        }
    }

    /**
     * Park until {@link #add} signals work.
     *
     * This used to sleep for 100 ms, which put a floor under every call made
     * from outside the VM: the spawned process waited for the next poll of an
     * idle scheduler. Waiting on the signal costs nothing when work arrives
     * promptly and still parks the thread when it does not.
     */
    private void sleep() {
        synchronized (workLock) {
            if (workPending) {
                workPending = false;
                return;
            }
            try {
                workLock.wait();
            } catch (InterruptedException e) {
                interrupt();
            }
            workPending = false;
        }
    }

}
