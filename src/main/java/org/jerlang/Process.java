package org.jerlang;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Deque;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.Stack;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import org.jerlang.type.Atom;
import org.jerlang.type.BitString;
import org.jerlang.type.Float;
import org.jerlang.type.Fun;
import org.jerlang.type.Integer;
import org.jerlang.type.List;
import org.jerlang.type.PID;
import org.jerlang.type.Reference;
import org.jerlang.type.Term;
import org.jerlang.type.Tuple;
import org.jerlang.type.stack.ExceptionHandler;
import org.jerlang.type.stack.StackFrame;
import org.jerlang.vm.Scheduler;

/**
 * Erlang is designed for massive concurrency.
 * Erlang processes are lightweight (grow and shrink dynamically)
 * with small memory footprint, fast to create and terminate,
 * and the scheduling overhead is low.
 *
 * Source:
 * http://www.erlang.org/doc/reference_manual/processes.html
 */
public class Process extends ProcessOrPort {

    /**
     * Fires the timers of `receive ... after Timeout` expressions. Daemon
     * threads, so a waiting process never keeps the VM alive.
     */
    private static final ScheduledThreadPoolExecutor WAIT_TIMERS = new ScheduledThreadPoolExecutor(1, runnable -> {
        Thread thread = new Thread(runnable, "jerlang-wait-timer");
        thread.setDaemon(true);
        return thread;
    });

    // See "erts/emulator/beam/erl_vm.h":
    private final int MAX_REG = 1024;
    private final int MAX_FREG = 32;

    private final LinkedBlockingQueue<Term> mailbox;
    private final ProcessDictionary dictionary;
    private Term[] registers = null;
    private Float[] fregisters = new Float[MAX_FREG];

    // Continuation Pointer
    private int cp = -1;
    private Stack<java.lang.Integer> cpStack = new Stack<>();

    // Stack / Y register
    private Term[] stack = new Term[16];
    private int sp = 0;

    // Signatures
    // We need a stack for the interpreter to find the correct labels
    private Stack<FunctionSignature> signatures = new Stack<>();

    // Used by `put_tuple/2` and `put/1`:
    private Tuple tuple;
    private int tupleIndex;

    // BitString, used by `bs_put_integer/5`
    private BitString bitString;

    // Exception handling
    private ExceptionHandler exceptionHandler = null;

    // Erlang function activations, youngest first
    private final Deque<StackFrame> frames = new ArrayDeque<>();

    // Set when an uncaught Throwable terminated this process
    private volatile Throwable failure = null;

    // Number of function applications that run synchronously on this process
    // without sharing its instruction stream, see Module.apply.
    private int applyDepth = 0;

    // Timeout
    private long timeout = 0;

    // Where a suspended receive loop resumes, and its pending timeout.
    private volatile Term waitLabel = null;
    private volatile boolean waitTimedOut = false;
    private long waitGeneration = 0;
    private ScheduledFuture<?> waitTimer = null;

    private static final Atom DOWN = Atom.of("DOWN");
    private static final Atom PROCESS = Atom.of("process");
    private static final Atom EXIT = Atom.of("EXIT");
    private static final Atom NORMAL = Atom.of("normal");
    private static final Atom KILL = Atom.of("kill");
    private static final Atom KILLED = Atom.of("killed");

    // Links are bidirectional, monitors are one-way and keyed by reference.
    // The maps hold pids rather than processes, so a terminated peer can be
    // resolved (and ignored) instead of being kept alive.
    private final Set<PID> links = ConcurrentHashMap.newKeySet();
    private final Map<Reference, PID> monitors = new ConcurrentHashMap<>();
    private final Map<Reference, PID> monitoredBy = new ConcurrentHashMap<>();

    private volatile boolean trapExit = false;
    private volatile Term pendingExit = null;
    private volatile boolean terminated = false;

    public Process(PID pid) {
        super(pid);
        dictionary = new ProcessDictionary();
        mailbox = new LinkedBlockingQueue<>();
    }

    public Process(PID pid, Fun fun) {
        this(pid);
    }

    public Process(PID pid, Atom module, Atom fun, List args) {
        this(pid);

        FunctionSignature s = new FunctionSignature(module, fun, Integer.of(args.length()));
        signatures.push(s);
        pushFrame(new StackFrame(module, fun, args.length()));

        int index = 0;
        while (args.length() > 0) {
            setX(index++, args.head());
            args = args.tail();
        }
    }

    public void allocate(int size, int keep) {
        Term[] newStack = new Term[stack.length + size];
        if (stack.length > 0) {
            System.arraycopy(stack, 0, newStack, 0, stack.length);
        }
        stack = newStack;
        sp += size;
    }

    public void allocate_heap(int stack, int heap, int live) {
        if (stack > 0) {
            allocate(stack, live);
        }
    }

    public void allocate_heap_zero(int stack, int heap, int live) {
        if (stack > 0) {
            allocate_zero(stack, live);
        }
    }

    public void allocate_zero(int size, int keep) {
        if (size > 0) {
            allocate(size, keep);
            Arrays.fill(stack, stack.length - size, stack.length, List.nil);
        }
    }

    public BitString bitString() {
        return bitString;
    }

    public void deallocate(int size) {
        if (size > 0) {
            Term[] newStack = new Term[stack.length - size];
            System.arraycopy(stack, 0, newStack, 0, newStack.length);
            stack = newStack;
            sp -= size;
        }
    }

    public ProcessDictionary dictionary() {
        return dictionary;
    }

    public boolean hasMessage() {
        return !mailbox.isEmpty();
    }

    public int getCP() {
        return cp;
    }

    public Float getFR(int index) {
        return fregisters[index];
    }

    public Term nextMessage() {
        return mailbox.peek();
    }

    public Tuple getTuple() {
        return tuple;
    }

    public int getTupleIndex() {
        return tupleIndex;
    }

    public Term getX(int index) {
        return registers()[index];
    }

    public Term getX(Integer index) {
        return getX(index.toInt());
    }

    public Term getY(int index) {
        return stack[sp - index];
    }

    public Term getY(Integer index) {
        return getY(index.toInt());
    }

    public void incrementTupleIndex() {
        tupleIndex++;
    }

    public Term popStack() {
        return stack[--sp];
    }

    public void pushStack(Term term) {
        stack[sp++] = term;
    }

    public void send(Term message) {
        try {
            mailbox.put(message);
            if (wake()) {
                scheduler().add(this);
            }
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
    }

    /**
     * A waiting process received a message: make it runnable again and
     * invalidate its pending timeout. Returns true when the caller has to
     * put the process back on a scheduler queue.
     */
    private synchronized boolean wake() {
        if (state() != ProcessState.WAITING) {
            return false;
        }
        waitGeneration++;
        waitTimedOut = false;
        if (waitTimer != null) {
            waitTimer.cancel(false);
            waitTimer = null;
        }
        // Stay WAITING: execute() dispatches a wakeup by resuming the
        // receive loop at waitLabel, instead of re-running the wait opcode.
        return true;
    }

    /**
     * Suspend in a receive loop. A later message resumes at Label; when
     * TimeoutMillis elapses first, execution resumes after the wait
     * instruction that saved the continuation pointer. A negative timeout
     * waits forever.
     */
    public synchronized void waitForMessage(Term label, long timeoutMillis) {
        waitLabel = label;
        waitTimedOut = false;
        waitGeneration++;
        if (waitTimer != null) {
            waitTimer.cancel(false);
            waitTimer = null;
        }
        setState(ProcessState.WAITING);
        if (timeoutMillis >= 0) {
            if (timeoutMillis == 0) {
                waitTimedOut = true;
            } else {
                long generation = waitGeneration;
                waitTimer = WAIT_TIMERS.schedule(() -> timeout(generation),
                    timeoutMillis, TimeUnit.MILLISECONDS);
            }
        }
    }

    private void timeout(long generation) {
        synchronized (this) {
            if (generation != waitGeneration || waitTimedOut) {
                return;
            }
            waitTimedOut = true;
            waitTimer = null;
            if (state() != ProcessState.WAITING) {
                return;
            }
            // Stay WAITING: execute() takes the timeout continuation.
        }
        scheduler().add(this);
    }

    public void setCP(int cp) {
        this.cp = cp;
    }

    public void setFR(Integer index, Float value) {
        fregisters[index.toInt()] = value;
    }

    public void setTuple(Tuple tuple) {
        this.tuple = tuple;
        this.tupleIndex = 0;
    }

    public void setX(int index, Term term) {
        // System.err.println("" + this + ": X" + index + " = " + term);
        registers()[index] = term;
    }

    public void setX(Integer index, Term term) {
        setX(index.toInt(), term);
    }

    public void setY(Integer index, Term term) {
        int pos = sp - index.toInt();
        stack[sp - index.toInt()] = term;
    }

    public FunctionSignature signature() {
        return signatures.peek();
    }

    public void signature(FunctionSignature signature) {
        signatures.pop();
        signatures.push(signature);
    }

    public void restoreCP() {
        cp = cpStack.pop();
    }

    public void storeCP() {
        cpStack.push(cp);
    }

    public int stackDepth() {
        return sp;
    }

    public int callsDepth() {
        return cpStack.size();
    }

    /**
     * Drop the stack frames above the given depths, so that an exception
     * handler runs in the frame that installed it and a following
     * `deallocate` returns to the right continuation pointer.
     */
    public void unwindTo(int stackDepth, int cpDepth) {
        while (cpStack.size() > cpDepth) {
            cpStack.pop();
        }
        cp = cpStack.isEmpty() ? 0 : cpStack.peek();
        if (sp > stackDepth) {
            sp = stackDepth;
        }
    }

    public ExceptionHandler exceptionHandler() {
        return exceptionHandler;
    }

    public void pushFrame(StackFrame frame) {
        frames.push(frame);
    }

    public void popFrame() {
        if (!frames.isEmpty()) {
            frames.pop();
        }
    }

    public StackFrame currentFrame() {
        return frames.peek();
    }

    public int framesDepth() {
        return frames.size();
    }

    public void unwindFramesTo(int depth) {
        while (frames.size() > depth) {
            frames.pop();
        }
    }

    /**
     * The stacktrace at this point, youngest call first. Each frame is
     * {Module, Function, Arity, Location}.
     */
    public List stacktrace() {
        List trace = List.nil;
        Iterator<StackFrame> iterator = frames.descendingIterator();
        while (iterator.hasNext()) {
            trace = new List(iterator.next().toTerm(), trace);
        }
        return trace;
    }

    public Throwable failure() {
        return failure;
    }

    public int applyDepth() {
        return applyDepth;
    }

    public void enterApply() {
        applyDepth++;
    }

    public void leaveApply() {
        applyDepth--;
    }

    public void setFailure(Throwable failure) {
        this.failure = failure;
    }

    @Override
    public void execute() {
        switch (state()) {
        case WAITING:
            if (hasMessage()) {
                setState(ProcessState.RUNNING);
                Term label = waitLabel;
                waitLabel = null;
                if (label != null) {
                    Interpreter.continueExecutionAtLabel(signatures.peek(), label);
                } else {
                    Interpreter.continueExecution(signatures.peek(), cp);
                }
            } else if (waitTimedOut) {
                setState(ProcessState.RUNNING);
                waitTimedOut = false;
                waitLabel = null;
                // cp points at wait_timeout: resume with the timeout code.
                Interpreter.continueExecution(signatures.peek(), cp + 1);
            }
            break;
        case RUNNABLE:
            Interpreter.continueExecution(signatures.peek(), cp);
            break;
        default:
            System.out.println("Skipping process " + pid() + " in state " + state());
            break;
        }
    }

    public boolean hasPendingExit() {
        return pendingExit != null;
    }

    public Term pendingExitReason() {
        return pendingExit;
    }

    public boolean trapExit() {
        return trapExit;
    }

    /**
     * Set trap_exit and return its previous value, like process_flag/2.
     */
    public synchronized boolean setTrapExit(boolean value) {
        boolean previous = trapExit;
        trapExit = value;
        return previous;
    }

    /**
     * Link to another process. Linking to a process that is already gone
     * is an immediate noproc exit signal, as in Erlang/OTP.
     */
    public void link(PID other) {
        ProcessOrPort peer = ProcessRegistry.resolve(other);
        Process target = peer instanceof Process ? (Process) peer : null;
        if (target == null || !target.addLink(pid())) {
            exitSignal(Atom.of("noproc"), other);
            return;
        }
        addLink(other);
    }

    public void unlink(PID other) {
        removeLink(other);
        ProcessOrPort peer = ProcessRegistry.resolve(other);
        if (peer instanceof Process) {
            ((Process) peer).removeLink(pid());
        }
    }

    /**
     * Monitor another process. Monitoring a process that already
     * terminated delivers DOWN with noproc straight away.
     */
    public Reference monitor(PID target) {
        Reference reference = new Reference();
        ProcessOrPort peer = ProcessRegistry.resolve(target);
        Process process = peer instanceof Process ? (Process) peer : null;
        if (process == null || !process.addMonitoredBy(reference, pid())) {
            send(Tuple.of(DOWN, reference, PROCESS, target, Atom.of("noproc")));
        } else {
            addMonitor(reference, target);
        }
        return reference;
    }

    /**
     * Remove a monitor. Returns true when the monitor or a queued DOWN
     * message was found.
     */
    public boolean demonitor(Reference reference, boolean flush) {
        PID target = monitorTarget(reference);
        boolean found = removeMonitor(reference);
        if (target != null) {
            ProcessOrPort peer = ProcessRegistry.resolve(target);
            if (peer instanceof Process) {
                ((Process) peer).removeMonitoredBy(reference);
            }
        }
        if (flush) {
            found |= flushDown(reference);
        }
        return found;
    }

    public synchronized boolean addLink(PID other) {
        if (terminated) {
            return false;
        }
        links.add(other);
        return true;
    }

    public synchronized void removeLink(PID other) {
        links.remove(other);
    }

    public synchronized boolean addMonitoredBy(Reference reference, PID watcher) {
        if (terminated) {
            return false;
        }
        monitoredBy.put(reference, watcher);
        return true;
    }

    public synchronized void removeMonitoredBy(Reference reference) {
        monitoredBy.remove(reference);
    }

    public synchronized void addMonitor(Reference reference, PID target) {
        monitors.put(reference, target);
    }

    public synchronized boolean removeMonitor(Reference reference) {
        return monitors.remove(reference) != null;
    }

    public synchronized PID monitorTarget(Reference reference) {
        return monitors.get(reference);
    }

    /**
     * Deliver an exit signal. trap_exit turns it into a message; otherwise a
     * non-normal reason becomes the pending exit of this process, which the
     * interpreter and the scheduler act on.
     */
    public void exitSignal(Term reason, PID from) {
        boolean waiting;
        synchronized (this) {
            if (terminated) {
                return;
            }
            if (trapExit) {
                send(Tuple.of(EXIT, from, reason));
                return;
            }
            if (NORMAL.equals(reason)) {
                return;
            }
            pendingExit = KILL.equals(reason) ? KILLED : reason;
            waiting = state() == ProcessState.WAITING;
        }
        if (waiting) {
            Scheduler scheduler = scheduler();
            if (scheduler != null) {
                scheduler.add(this);
            }
        }
    }

    /**
     * Record the final reason of this process and return what has to be
     * notified. Returns null when the process already terminated.
     */
    public synchronized Termination terminate(Term reason) {
        if (terminated) {
            return null;
        }
        terminated = true;
        setState(ProcessState.EXITING);
        if (waitTimer != null) {
            waitTimer.cancel(false);
            waitTimer = null;
        }
        Termination termination = new Termination(pid(), reason,
            new ArrayList<>(links), new HashMap<>(monitoredBy), new HashMap<>(monitors));
        links.clear();
        monitoredBy.clear();
        monitors.clear();
        return termination;
    }

    public boolean isTerminated() {
        return terminated;
    }

    private boolean flushDown(Reference reference) {
        for (Term message : mailbox) {
            if (message instanceof Tuple) {
                Tuple tuple = (Tuple) message;
                if (tuple.arity() == 5 && DOWN.equals(tuple.element(1))
                    && reference.equals(tuple.element(2))) {
                    return mailbox.remove(message);
                }
            }
        }
        return false;
    }

    /**
     * The links and monitors a terminating process had, captured exactly
     * once so that every peer hears about the exit one time.
     */
    public static final class Termination {

        public final PID pid;
        public final Term reason;
        public final java.util.List<PID> links;
        public final Map<Reference, PID> monitoredBy;
        public final Map<Reference, PID> monitors;

        Termination(PID pid, Term reason, java.util.List<PID> links,
            Map<Reference, PID> monitoredBy, Map<Reference, PID> monitors) {
            this.pid = pid;
            this.reason = reason;
            this.links = links;
            this.monitoredBy = monitoredBy;
            this.monitors = monitors;
        }

    }

    public void loop_rec_end() {
        // TODO: this is technically not correct
        // TODO: we need to advance the message index instead
        mailbox.remove();
    }

    public Term[] registers() {
        if (registers == null) {
            registers = new Term[MAX_REG];
        }
        return registers;
    }

    public void setBitString(BitString bitString) {
        this.bitString = bitString;
    }

    public void setExceptionHandler(ExceptionHandler exceptionHandler) {
        this.exceptionHandler = exceptionHandler;
    }

    // TODO: Only for debugging, should be removed before 1.0
    public void printStack() {
        System.out.print("REG: [" + registers()[0] + ", ");
        System.out.print("" + registers()[1] + ", ");
        System.out.print("" + registers()[2] + ", ");
        System.out.print("" + registers()[3] + "] ");

        System.out.print("STACK(" + stack.length + "): ");
        for (Term t : stack) {
            System.out.print("" + t + ", ");
        }
        System.out.println();
    }

    /**
     * Remove the message the receive loop is looking at. Like the BEAM
     * instruction, this does not touch any register.
     */
    public void removeMessage() {
        mailbox.remove();
        // TODO: remove timer
    }

    public void popSignature() {
        signatures.pop();
    }

    public void pushSignature(FunctionSignature signature) {
        signatures.push(signature);
    }

    public void clearTimeout() {
        timeout = 0;
    }

    public void setTimeout() {
        timeout = System.currentTimeMillis();
    }

    @Override
    public Process toProcess() {
        return this;
    }

    @Override
    public String toString() {
        return pid().toString();
    }

    public void resetMailbox() {
        // TODO
    }

}
