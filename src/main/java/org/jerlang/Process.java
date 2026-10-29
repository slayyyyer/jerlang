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
import java.util.concurrent.atomic.AtomicLong;

import org.jerlang.erts.dist.Distribution;
import org.jerlang.erts.dist.NodeMonitorOptions;
import org.jerlang.erts.erlang.Error;
import org.jerlang.type.Atom;
import org.jerlang.type.BitString;
import org.jerlang.type.Float;
import org.jerlang.type.Fun;
import org.jerlang.type.Integer;
import org.jerlang.type.List;
import org.jerlang.type.PID;
import org.jerlang.type.Reference;
import org.jerlang.type.PidOrPortId;
import org.jerlang.type.Term;
import org.jerlang.type.Tuple;
import org.jerlang.type.stack.ExceptionHandler;
import org.jerlang.type.stack.StackFrame;
import org.jerlang.vm.Scheduler;
import org.jerlang.vm.VirtualMachine;

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

    // The mailbox position of the current receive loop. Messages before
    // this position did not match the clauses already tried and stay in
    // the mailbox for later receives, as Erlang/OTP requires.
    private int receivePosition = 0;
    private ScheduledFuture<?> waitTimer = null;

    private static final Atom DOWN = Atom.of("DOWN");
    private static final Atom PROCESS = Atom.of("process");
    private static final Atom EXIT = Atom.of("EXIT");
    private static final Atom NORMAL = Atom.of("normal");
    private static final Atom KILL = Atom.of("kill");
    private static final Atom KILLED = Atom.of("killed");
    private static final Atom NOCONNECTION = Atom.of("noconnection");

    // Unlink identifiers are unique among the outstanding unlink operations
    // of a node, which is all the link protocol asks for.
    private static final AtomicLong UNLINK_IDS = new AtomicLong();

    // Links are bidirectional, monitors are one-way and keyed by reference.
    // The maps hold pids rather than processes, so a terminated peer can be
    // resolved (and ignored) instead of being kept alive. Links to other
    // nodes carry the extra state the OTP 26 link protocol needs.
    private final Set<PID> links = ConcurrentHashMap.newKeySet();
    private final Map<PID, RemoteLink> remoteLinks = new ConcurrentHashMap<>();
    private final Map<Reference, Term> monitors = new ConcurrentHashMap<>();
    private final Map<Reference, Monitor> monitoredBy = new ConcurrentHashMap<>();

    // One count per monitor_node(Node, true) call. A node failure clears
    // the subscription, as Erlang/OTP does, so a later reconnection does
    // not produce another nodedown for the same call.
    private final Map<Atom, java.lang.Integer> monitoredNodes = new ConcurrentHashMap<>();

    // One count per net_kernel:monitor_nodes(Flag, Options) subscription.
    // The options are part of the key, as in OTP, so unsubscribing with
    // the same options removes every subscription made with them.
    private final Map<NodeMonitorOptions, java.lang.Integer> nodeMonitorSubscriptions =
        new ConcurrentHashMap<>();

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

        // A process started with a fun begins at the lambda's label, with
        // the free variables in X0..X(NumFree-1), exactly as call_fun does
        // when the fun is applied in the current process.
        FunctionSignature signature = fun.signature();
        signatures.push(signature);
        pushFrame(new StackFrame(signature.module(), signature.function(), 0));
        Term[] saved = fun.savedRegisters();
        for (int index = 0; index < saved.length; index++) {
            setX(index, saved[index]);
        }
        Module module = ModuleRegistry.get(signature.module());
        int label = fun.label().toRegisterIndex().toInt();
        java.lang.Integer start = module.beamData().codeChunk().labels().get(label);
        if (start == null) {
            throw new Error("No instruction for fun label " + label);
        }
        cp = start;
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
        return receivePosition < mailbox.size();
    }

    public int getCP() {
        return cp;
    }

    public Float getFR(int index) {
        return fregisters[index];
    }

    public Term nextMessage() {
        int index = 0;
        for (Term message : mailbox) {
            if (index++ == receivePosition) {
                return message;
            }
        }
        return null;
    }

    /**
     * Wait until a message matching `predicate` is in the mailbox, remove
     * it, and return it. Messages that do not match stay in order.
     *
     * This is used by calls that need to wait for a protocol reply from
     * inside a BIF, such as a remote RPC. It returns null on timeout.
     */
    public Term awaitMessage(java.util.function.Predicate<Term> predicate, long timeoutMillis) {
        long deadline = timeoutMillis < 0 ? Long.MAX_VALUE
            : System.currentTimeMillis() + timeoutMillis;
        while (true) {
            for (Term message : mailbox) {
                if (predicate.test(message) && mailbox.remove(message)) {
                    return message;
                }
            }
            long remaining = deadline == Long.MAX_VALUE ? Long.MAX_VALUE
                : deadline - System.currentTimeMillis();
            if (remaining <= 0) {
                return null;
            }
            try {
                Thread.sleep(Math.min(remaining, 5));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return null;
            }
        }
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
                // A timed-out receive leaves its scan position behind; the
                // next receive starts again at the head of the mailbox.
                receivePosition = 0;
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
     * is an immediate noproc exit signal, as in Erlang/OTP. A process on
     * another node gets a LINK signal instead.
     */
    public void link(PID other) {
        if (!onThisNode(other)) {
            linkRemote(other);
            return;
        }
        ProcessOrPort peer = ProcessRegistry.resolve(other);
        Process target = peer instanceof Process ? (Process) peer : null;
        if (target == null || !target.addLink(pid())) {
            exitSignal(Atom.of("noproc"), other);
            return;
        }
        addLink(other);
    }

    public void unlink(PID other) {
        if (!onThisNode(other)) {
            unlinkRemote(other);
            return;
        }
        removeLink(other);
        ProcessOrPort peer = ProcessRegistry.resolve(other);
        if (peer instanceof Process) {
            ((Process) peer).removeLink(pid());
        }
    }

    /**
     * Monitor another process. Monitoring a process that already
     * terminated delivers DOWN with noproc straight away. A process on
     * another node gets a MONITOR_P signal, and DOWN with noconnection
     * when that node can not be reached.
     */
    public Reference monitor(PID target) {
        Reference reference = new Reference();
        if (!onThisNode(target)) {
            addMonitor(reference, target);
            if (!Distribution.sendMonitor(pid(), target, target.node(), reference)) {
                removeMonitor(reference);
                send(Tuple.of(DOWN, reference, PROCESS, target, NOCONNECTION));
            }
            return reference;
        }
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
     * Monitor a registered name on a node, as
     * erlang:monitor(process, {Name, Node}) does. The DOWN message reports
     * the object as {Name, Node}, like Erlang/OTP.
     */
    public Reference monitor(Atom name, Atom node) {
        Reference reference = new Reference();
        if (!node.equals(VirtualMachine.instance().node())) {
            Tuple target = Tuple.of(name, node);
            addMonitor(reference, target);
            if (!Distribution.sendMonitor(pid(), name, node, reference)) {
                removeMonitor(reference);
                send(Tuple.of(DOWN, reference, PROCESS, target, NOCONNECTION));
            }
            return reference;
        }
        PidOrPortId id = ProcessRegistry.instance().get(name);
        ProcessOrPort peer = id == null ? null : ProcessRegistry.resolve(id);
        if (peer instanceof Process) {
            Process process = (Process) peer;
            if (process.addMonitoredBy(reference, pid())) {
                addMonitor(reference, process.pid());
                return reference;
            }
        }
        send(Tuple.of(DOWN, reference, PROCESS, Tuple.of(name, node), Atom.of("noproc")));
        return reference;
    }

    /**
     * Monitor the status of a node, one independent subscription per
     * monitor_node(Node, true) call. A node that can not be reached
     * reports nodedown immediately. The subscription ends when the node
     * goes down.
     *
     * http://www.erlang.org/doc/man/erlang.html#monitor_node-2
     */
    public void monitorNode(Atom node, boolean flag) {
        if (flag) {
            if (node.equals(VirtualMachine.instance().node())) {
                return;
            }
            if (!Distribution.isConnected(node) && !Distribution.connect(node)) {
                send(Tuple.of(Atom.of("nodedown"), node));
                return;
            }
            monitoredNodes.merge(node, 1, java.lang.Integer::sum);
            if (!Distribution.isConnected(node)) {
                // The connection went away between connect/1 and the
                // subscription. Clear the subscription and report it as
                // down instead of keeping a monitor on a dead node.
                decrementNodeSubscription(node);
                send(Tuple.of(Atom.of("nodedown"), node));
            }
            return;
        }
        decrementNodeSubscription(node);
    }

    /**
     * Subscribe to the node events that net_kernel:monitor_nodes/1
     * delivers. This is the legacy form with only visible nodes and
     * two-element messages.
     */
    public boolean monitorNodeEvents(boolean flag) {
        return monitorNodeEvents(NodeMonitorOptions.legacy(), flag) > 0;
    }

    /**
     * Add or remove one subscription for a set of monitor_nodes options.
     * Returns the number of subscriptions that existed before the call.
     */
    public int monitorNodeEvents(NodeMonitorOptions options, boolean flag) {
        synchronized (nodeMonitorSubscriptions) {
            java.lang.Integer previous = nodeMonitorSubscriptions.get(options);
            int count = previous == null ? 0 : previous;
            if (flag) {
                nodeMonitorSubscriptions.put(options, count + 1);
            } else if (count <= 1) {
                nodeMonitorSubscriptions.remove(options);
            } else {
                nodeMonitorSubscriptions.put(options, count - 1);
            }
            return count;
        }
    }

    private synchronized int decrementNodeSubscription(Atom node) {
        java.lang.Integer count = monitoredNodes.get(node);
        if (count == null) {
            return -1;
        }
        if (count <= 1) {
            monitoredNodes.remove(node);
            return 0;
        }
        monitoredNodes.put(node, count - 1);
        return count - 1;
    }

    /**
     * A distribution connection to `node` was established. Every
     * monitor_node subscription gets its two-element message, and every
     * monitor_nodes subscription whose node_type includes this connection
     * gets the message shape it asked for.
     */
    public void nodeUp(Atom node, boolean hidden, int connectionId) {
        java.lang.Integer count = monitoredNodes.get(node);
        if (count != null) {
            for (int index = 0; index < count; index++) {
                send(Tuple.of(Atom.of("nodeup"), node));
            }
        }
        for (Map.Entry<NodeMonitorOptions, java.lang.Integer> entry
            : nodeMonitorSubscriptions.entrySet()) {
            if (entry.getKey().includes(hidden)) {
                for (int index = 0; index < entry.getValue(); index++) {
                    send(entry.getKey().message(Atom.of("nodeup"), node, hidden,
                        connectionId, null));
                }
            }
        }
    }

    /**
     * A distribution connection to `node` is gone. Every outstanding
     * monitor_node subscription gets one nodedown, and monitor_nodes
     * subscribers get the message shape their options asked for.
     * monitor_node subscriptions are one-shot and are cleared here.
     */
    public void nodeDown(Atom node, boolean hidden, int connectionId, Term reason) {
        java.lang.Integer count = monitoredNodes.remove(node);
        if (count != null) {
            for (int index = 0; index < count; index++) {
                send(Tuple.of(Atom.of("nodedown"), node));
            }
        }
        for (Map.Entry<NodeMonitorOptions, java.lang.Integer> entry
            : nodeMonitorSubscriptions.entrySet()) {
            if (entry.getKey().includes(hidden)) {
                for (int index = 0; index < entry.getValue(); index++) {
                    send(entry.getKey().message(Atom.of("nodedown"), node, hidden,
                        connectionId, reason));
                }
            }
        }
    }

    /**
     * Remove a monitor. Returns true when the monitor or a queued DOWN
     * message was found.
     */
    public boolean demonitor(Reference reference, boolean flush) {
        Term target = monitorTarget(reference);
        boolean found = removeMonitor(reference);
        if (target instanceof PID) {
            PID pid = (PID) target;
            if (onThisNode(pid)) {
                ProcessOrPort peer = ProcessRegistry.resolve(pid);
                if (peer instanceof Process) {
                    ((Process) peer).removeMonitoredBy(reference);
                }
            } else {
                Distribution.sendDemonitor(pid(), pid, pid.node(), reference);
            }
        } else if (target instanceof Tuple) {
            Tuple nameAndNode = (Tuple) target;
            if (nameAndNode.arity() == 2 && nameAndNode.element(1) instanceof Atom
                && nameAndNode.element(2) instanceof Atom) {
                Distribution.sendDemonitor(pid(), nameAndNode.element(1),
                    nameAndNode.element(2).toAtom(), reference);
            }
        }
        if (flush) {
            found |= flushDown(reference);
        }
        return found;
    }

    /**
     * True when a PID names a process on this node. A PID from an older
     * incarnation of the node also counts, because it names no process at
     * all rather than one on a peer.
     */
    public static boolean onThisNode(PID pid) {
        return pid.node().equals(VirtualMachine.instance().node());
    }

    /**
     * Link to a process on another node. The link is recorded first, so
     * that an immediate signal from the peer is never missed, and the LINK
     * signal is only sent when there is no active link yet.
     */
    private void linkRemote(PID other) {
        boolean signal;
        synchronized (this) {
            if (terminated) {
                return;
            }
            RemoteLink link = remoteLinks.get(other);
            signal = link == null || !link.active;
            if (link == null) {
                link = new RemoteLink();
                remoteLinks.put(other, link);
            }
            link.active = true;
            link.unlinkId = 0;
            links.add(other);
        }
        if (signal && !Distribution.sendLink(pid(), other)) {
            synchronized (this) {
                RemoteLink link = remoteLinks.get(other);
                if (link != null && link.active) {
                    remoteLinks.remove(other);
                    links.remove(other);
                }
            }
            exitSignal(NOCONNECTION, other);
        }
    }

    /**
     * Request the end of a link to another node. The link stays around,
     * inactive, until the peer acknowledges the UNLINK_ID signal.
     */
    private void unlinkRemote(PID other) {
        long id;
        synchronized (this) {
            RemoteLink link = remoteLinks.get(other);
            if (link == null || !link.active) {
                return;
            }
            id = UNLINK_IDS.incrementAndGet();
            if (id <= 0) {
                UNLINK_IDS.set(1);
                id = 1;
            }
            link.active = false;
            link.unlinkId = id;
            links.remove(other);
        }
        Distribution.sendUnlink(pid(), other, id);
    }

    /**
     * Record a link that the peer already set up, as the `link` flag of a
     * SPAWN_REPLY announces for an atomically linked remote spawn. No LINK
     * signal is sent: the peer created the link together with the process.
     */
    public synchronized void remoteLinkEstablished(PID other) {
        if (terminated) {
            return;
        }
        RemoteLink link = remoteLinks.get(other);
        if (link == null) {
            link = new RemoteLink();
            remoteLinks.put(other, link);
        }
        link.active = true;
        link.unlinkId = 0;
        links.add(other);
    }

    /**
     * A LINK signal arrived from another node. A second LINK for a link
     * that is already known is ignored: the peer has not acknowledged our
     * UNLINK_ID yet, so the two operations must not resurrect the link.
     * Returns false when this process is already gone.
     */
    public synchronized boolean linkReceived(PID from) {
        if (terminated) {
            return false;
        }
        if (remoteLinks.containsKey(from)) {
            return true;
        }
        RemoteLink link = new RemoteLink();
        link.active = true;
        remoteLinks.put(from, link);
        links.add(from);
        return true;
    }

    /**
     * A UNLINK_ID signal arrived. The acknowledgement has already been
     * sent. An outstanding unlink of our own stays in place: the peer will
     * acknowledge it, and only then is the link information gone.
     */
    public synchronized void unlinkIdReceived(PID from, long id) {
        RemoteLink link = remoteLinks.get(from);
        if (link != null && link.active) {
            remoteLinks.remove(from);
            links.remove(from);
        }
    }

    /**
     * The peer acknowledged our UNLINK_ID, so the link information can go.
     */
    public synchronized void unlinkIdAckReceived(PID from, long id) {
        RemoteLink link = remoteLinks.get(from);
        if (link != null && !link.active && link.unlinkId == id) {
            remoteLinks.remove(from);
            links.remove(from);
        }
    }

    /**
     * A legacy UNLINK signal arrived. It has no acknowledgement and always
     * removes the link information.
     */
    public synchronized void remoteUnlinkReceived(PID from) {
        remoteLinks.remove(from);
        links.remove(from);
    }

    /**
     * An exit signal arrived from another node. A signal that came from a
     * link is only acted on while that link is active: an inactive link is
     * waiting for its UNLINK_ID_ACK and ignores it.
     */
    public void exitReceived(PID from, Term reason, boolean explicit) {
        if (explicit) {
            exitSignal(reason, from);
            return;
        }
        synchronized (this) {
            RemoteLink link = remoteLinks.get(from);
            if (link == null || !link.active) {
                return;
            }
            remoteLinks.remove(from);
            links.remove(from);
        }
        exitSignal(reason, from);
    }

    /**
     * The connection to a node is gone: every link to it receives an exit
     * signal with reason noconnection and every monitor on it reports DOWN
     * with noconnection, as Erlang/OTP does.
     */
    public void connectionLost(Atom node) {
        ArrayList<PID> lostLinks = new ArrayList<>();
        ArrayList<Tuple> lostMonitors = new ArrayList<>();
        synchronized (this) {
            for (PID link : new ArrayList<>(remoteLinks.keySet())) {
                if (link.node().equals(node)) {
                    RemoteLink state = remoteLinks.remove(link);
                    links.remove(link);
                    if (state != null && state.active) {
                        lostLinks.add(link);
                    }
                }
            }
            for (Map.Entry<Reference, Term> monitor : new ArrayList<>(monitors.entrySet())) {
                Term target = monitor.getValue();
                Atom targetNode = null;
                if (target instanceof PID) {
                    targetNode = ((PID) target).node();
                } else if (target instanceof Tuple && ((Tuple) target).arity() == 2) {
                    targetNode = ((Tuple) target).element(2).toAtom();
                }
                if (node.equals(targetNode)) {
                    monitors.remove(monitor.getKey());
                    lostMonitors.add(Tuple.of(monitor.getKey(), target));
                }
            }
        }
        for (PID link : lostLinks) {
            exitSignal(NOCONNECTION, link);
        }
        for (Tuple monitor : lostMonitors) {
            send(Tuple.of(DOWN, monitor.element(1), PROCESS, monitor.element(2), NOCONNECTION));
        }
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
        return addMonitoredBy(reference, watcher, null);
    }

    /**
     * A process started monitoring this one, possibly through a registered
     * name. The name is what MONITOR_P_EXIT reports back to the peer.
     */
    public synchronized boolean addMonitoredBy(Reference reference, PID watcher, Atom name) {
        if (terminated) {
            return false;
        }
        monitoredBy.put(reference, new Monitor(watcher, name));
        return true;
    }

    public synchronized void removeMonitoredBy(Reference reference) {
        monitoredBy.remove(reference);
    }

    public synchronized void addMonitor(Reference reference, Term target) {
        monitors.put(reference, target);
    }

    public synchronized boolean removeMonitor(Reference reference) {
        return monitors.remove(reference) != null;
    }

    public synchronized Term monitorTarget(Reference reference) {
        return monitors.get(reference);
    }

    /**
     * Deliver an exit signal. trap_exit turns it into a message; otherwise a
     * non-normal reason becomes the pending exit of this process, which the
     * interpreter and the scheduler act on.
     *
     * `kill` is uncatchable, so it is checked before trap_exit: a process
     * that traps exits still dies of an `exit(Pid, kill)` with reason
     * `killed`, as in Erlang/OTP. Checking trap_exit first made the signal
     * depend on whether the target had already run its process_flag call.
     */
    public void exitSignal(Term reason, PID from) {
        boolean waiting;
        synchronized (this) {
            if (terminated) {
                return;
            }
            if (KILL.equals(reason)) {
                pendingExit = KILLED;
                waiting = state() == ProcessState.WAITING;
            } else if (trapExit) {
                send(Tuple.of(EXIT, from, reason));
                return;
            } else if (NORMAL.equals(reason)) {
                return;
            } else {
                pendingExit = reason;
                waiting = state() == ProcessState.WAITING;
            }
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
        public final Map<Reference, Monitor> monitoredBy;
        public final Map<Reference, Term> monitors;

        Termination(PID pid, Term reason, java.util.List<PID> links,
            Map<Reference, Monitor> monitoredBy, Map<Reference, Term> monitors) {
            this.pid = pid;
            this.reason = reason;
            this.links = links;
            this.monitoredBy = monitoredBy;
            this.monitors = monitors;
        }

    }

    /**
     * The process local state of a link to another node, as the OTP 26 link
     * protocol defines it: the link is active until an unlink is requested,
     * and the outstanding unlink identifier identifies the request that the
     * peer still has to acknowledge.
     */
    private static final class RemoteLink {
        private boolean active;
        private long unlinkId;
    }

    /**
     * A monitor that another process holds on this one. The name is set
     * when a peer monitors a registered name rather than a PID.
     */
    public static final class Monitor {

        public final PID watcher;
        public final Atom name;

        Monitor(PID watcher, Atom name) {
            this.watcher = watcher;
            this.name = name;
        }

    }

    public void loop_rec_end() {
        // This message did not match. Keep it in the mailbox and advance
        // the scan position to the next one.
        receivePosition++;
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
        Iterator<Term> iterator = mailbox.iterator();
        int index = 0;
        while (iterator.hasNext()) {
            iterator.next();
            if (index++ == receivePosition) {
                iterator.remove();
                break;
            }
        }
        receivePosition = 0;
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
