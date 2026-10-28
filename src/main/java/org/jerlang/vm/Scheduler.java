package org.jerlang.vm;

import java.util.HashSet;

import org.jerlang.Process;
import org.jerlang.ProcessOrPort;
import org.jerlang.ProcessRegistry;
import org.jerlang.ProcessState;
import org.jerlang.type.PidOrPortId;

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
            Throwable failure = null;
            try {
                p.execute();
            } catch (Throwable t) {
                // An uncaught error terminates the process, but must not
                // terminate the scheduler thread that ran it.
                failure = t;
            } finally {
                ProcessRegistry.self(null);
            }
            if (failure != null) {
                if (p instanceof Process) {
                    ((Process) p).setFailure(failure);
                }
                p.setState(ProcessState.EXITING);
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

    private void sleep() {
        try {
            Thread.sleep(100);
        } catch (InterruptedException e) {
            interrupt();
        }
    }

}
