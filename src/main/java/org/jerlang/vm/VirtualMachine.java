package org.jerlang.vm;

import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.jerlang.Process;
import org.jerlang.ProcessRegistry;
import org.jerlang.erts.erlang.Error;
import org.jerlang.type.Atom;
import org.jerlang.type.Fun;
import org.jerlang.type.List;
import org.jerlang.type.PID;
import org.jerlang.type.PortID;
import org.jerlang.type.Term;
import org.jerlang.type.TimerReference;
import org.jerlang.type.Tuple;

/**
 *
 */
public class VirtualMachine {

    private Scheduler[] schedulers;
    private volatile int nextScheduler = 0;

    private static final AtomicInteger nextPID = new AtomicInteger();

    private ScheduledThreadPoolExecutor timerExecutor;

    // Identity of this node. A node starts out unnamed, exactly like an
    // Erlang node that was not started with -name or -sname.
    private volatile Atom node = Atom.of("nonode@nohost");
    private volatile int nodeCreation = 0;

    private static final VirtualMachine VIRTUAL_MACHINE = new VirtualMachine();

    private VirtualMachine() {
        int processors = Runtime.getRuntime().availableProcessors();
        schedulers = new Scheduler[processors];
        for (int index = 0; index < processors; index++) {
            schedulers[index] = new Scheduler();
        }
        timerExecutor = new ScheduledThreadPoolExecutor(processors);
    }

    public Term cancel(TimerReference timerReference) {
        timerReference.future().cancel(false);
        return Tuple.of(Atom.of("ok"), Atom.of("cancel"));
    }

    public static Process self() {
        return (Process) ProcessRegistry.self();
    }

    public void start() {
        int processors = Runtime.getRuntime().availableProcessors();
        schedulers = new Scheduler[processors];
        for (int index = 0; index < processors; index++) {
            schedulers[index] = new Scheduler();
            schedulers[index].start();
        }
        timerExecutor = new ScheduledThreadPoolExecutor(processors);
        ProcessRegistry.instance().cleanup();
        org.jerlang.erts.dist.Distribution.start();
    }

    public void shutdown() {
        org.jerlang.erts.dist.Distribution.shutdown();
        for (Scheduler scheduler : schedulers) {
            scheduler.interrupt();
        }
        for (Scheduler scheduler : schedulers) {
            try {
                scheduler.join();
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
        }
        ProcessRegistry.instance().cleanup();
    }

    public static VirtualMachine instance() {
        return VIRTUAL_MACHINE;
    }

    /**
     * The name of this node, or `nonode@nohost` if it has no name yet.
     */
    public Atom node() {
        return node;
    }

    /**
     * The creation number of this node. It changes when the node is
     * restarted with the same name, so that stale PIDs and references
     * from the previous incarnation do not match the new one.
     */
    public int nodeCreation() {
        return nodeCreation;
    }

    /**
     * Name this node, as -name and -sname do.
     */
    public void setNode(Atom node) {
        this.node = node;
    }

    public void setNode(Atom node, int creation) {
        this.node = node;
        this.nodeCreation = creation;
    }

    public Process resolve(PID pid) {
        return (Process) ProcessRegistry.resolve(pid);
    }

    public TimerReference send_after(int time, Term message, PID pid) {
        return new TimerReference(timerExecutor.schedule(
            new Runnable() {

                @Override
                public void run() {
                    resolve(pid).send(message);
                }

            }, time, TimeUnit.MILLISECONDS));
    }

    public Process spawn(Fun fun) {
        Process process = prepare(fun);
        schedule(process);
        return process;
    }

    public Process spawn(Atom module, Atom fun, List args) {
        Process process = prepare(module, fun, args);
        schedule(process);
        return process;
    }

    /**
     * Create a process without putting it on a run queue. A remote spawn
     * uses this to send its SPAWN_REPLY, with the link and monitor already
     * in place, before the new process can run and terminate.
     */
    public Process prepare(Fun fun) {
        PID pid = new PID(node, nextPID.incrementAndGet(), 0, nodeCreation);
        Process process = new Process(pid, fun);
        ProcessRegistry.register(process);
        return process;
    }

    public Process prepare(Atom module, Atom fun, List args) {
        PID pid = new PID(node, nextPID.incrementAndGet(), 0, nodeCreation);
        Process process = new Process(pid, module, fun, args);
        ProcessRegistry.register(process);
        return process;
    }

    /**
     * Put a prepared process on a run queue.
     */
    public void schedule(Process process) {
        // Round-robin process assignment
        schedulers[nextScheduler++ % schedulers.length].add(process);
    }

    /**
     * A PID that names no process. erlang:spawn/4 returns one when the
     * target node can not be reached, like Erlang/OTP.
     */
    public PID uselessPid() {
        return new PID(node, nextPID.incrementAndGet(), 0, nodeCreation);
    }

    public PortID spawn_port(Term command, List portSettings) {
        throw new Error("not implemented");
    }

    public PortID spawn_driver_port(Term command, List portSettings) {
        throw new Error("not implemented");
    }

    public PortID spawn_executable_port(Term fileName, List portSettings) {
        throw new Error("not implemented");
    }

}
