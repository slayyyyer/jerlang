package org.jerlang;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

import org.jerlang.erts.erlang.Error;
import org.jerlang.type.Atom;
import org.jerlang.type.Boolean;
import org.jerlang.type.Integer;
import org.jerlang.type.List;
import org.jerlang.type.PID;
import org.jerlang.type.Term;
import org.jerlang.type.Tuple;

/**
 * erlang:trace/3 and erlang:trace_pattern/3.
 *
 * Tracing is deliberately small: a process carries the flags it was traced
 * with, and a global pattern table says which functions produce call events.
 * A trace event is an ordinary message to the tracer, so the runtime needs no
 * separate event loop to make `dbg` and a test able to observe calls and
 * messages.
 *
 * Only call, return_to, send and receive are produced. The other OTP trace
 * flags are accepted so a caller can pass a standard flag list, but they do
 * not generate events yet.
 */
public final class Tracing {

    public static final Atom TRACE = Atom.of("trace");
    public static final Atom CALL = Atom.of("call");
    public static final Atom RETURN_TO = Atom.of("return_to");
    public static final Atom SEND = Atom.of("send");
    public static final Atom RECEIVE = Atom.of("receive");
    public static final Atom TRACER = Atom.of("tracer");

    private static final Set<Atom> FLAGS = ConcurrentHashMap.newKeySet();
    private static final Map<FunctionSignature, Pattern> EXACT = new ConcurrentHashMap<>();
    private static final Map<Atom, Pattern> MODULES = new ConcurrentHashMap<>();
    private static volatile Pattern ALL = null;
    private static volatile boolean anyPattern = false;
    private static volatile Settings newProcessTrace = null;

    static {
        for (String flag : new String[] {
            "call", "return_to", "send", "receive", "procs", "running", "exiting",
            "garbage_collection", "timestamp", "monotonic_timestamp",
            "cpu_timestamp", "arity", "silent", "set_on_spawn",
            "set_on_first_spawn", "set_on_link", "set_on_first_link",
            "running_procs", "running_ports"
        }) {
            FLAGS.add(Atom.of(flag));
        }
    }

    private Tracing() {
    }

    /**
     * The settings of one trace/3 call: the flags to add or remove and the
     * tracer they report to.
     */
    public static final class Settings {

        private final Set<Atom> flags;
        private final PID tracer;

        private Settings(Set<Atom> flags, PID tracer) {
            this.flags = flags;
            this.tracer = tracer;
        }

        public Set<Atom> flags() {
            return flags;
        }

        public PID tracer() {
            return tracer;
        }

    }

    /**
     * One enable/disable decision for a function, module or the whole node.
     */
    public static final class Pattern {

        private final boolean enabled;
        private final boolean returnTrace;
        private final AtomicLong calls = new AtomicLong();

        private Pattern(boolean enabled, boolean returnTrace) {
            this.enabled = enabled;
            this.returnTrace = returnTrace;
        }

        public boolean enabled() {
            return enabled;
        }

        public boolean returnTrace() {
            return returnTrace;
        }

        public long calls() {
            return calls.get();
        }

    }

    /**
     * Parse the flag list of trace/3. `{tracer, Pid}` selects the receiver;
     * every other tuple form is accepted but ignored, so a standard dbg flag
     * list does not fail here.
     */
    public static Settings parse(List flags, PID defaultTracer) {
        Set<Atom> parsed = ConcurrentHashMap.newKeySet();
        PID tracer = defaultTracer;
        List rest = flags;
        while (rest.length() > 0) {
            Term flag = rest.head();
            rest = rest.tail();
            if (flag instanceof Atom) {
                if (!FLAGS.contains(flag)) {
                    throw Error.badarg;
                }
                parsed.add((Atom) flag);
            } else if (flag instanceof Tuple && ((Tuple) flag).arity() == 2
                && ((Tuple) flag).element(1).equals(TRACER)
                && ((Tuple) flag).element(2) instanceof PID) {
                tracer = (PID) ((Tuple) flag).element(2);
            } else if (flag instanceof Tuple && ((Tuple) flag).arity() == 3
                && ((Tuple) flag).element(1).equals(TRACER)) {
                // {tracer, Module, State}: a tracer implemented by a module
                // is not supported, but accepting it keeps a dbg start from
                // failing before the first useful trace.
                tracer = defaultTracer;
            } else {
                throw Error.badarg;
            }
        }
        return new Settings(parsed, tracer);
    }

    /**
     * Add or remove the parsed flags on one process.
     */
    public static void apply(Process process, boolean enable, Settings settings) {
        if (!enable && settings.flags().isEmpty()) {
            process.clearTraceFlags();
            process.setTraceTracer(null);
            return;
        }
        for (Atom flag : settings.flags()) {
            process.setTraceFlag(flag, enable);
        }
        if (enable) {
            process.setTraceTracer(settings.tracer());
        } else if (process.traceTracer() != null
            && process.traceTracer().equals(settings.tracer())
            && noFlags(process)) {
            process.setTraceTracer(null);
        }
    }

    private static boolean noFlags(Process process) {
        for (Atom flag : FLAGS) {
            if (process.traceFlag(flag)) {
                return false;
            }
        }
        return true;
    }

    /**
     * `trace(new, ...)` applies to processes created after the call. A later
     * `trace(new, false, ...)` turns it off.
     */
    public static void traceNew(boolean enable, Settings settings) {
        newProcessTrace = enable ? settings : null;
    }

    public static void applyToNewProcess(Process process) {
        Settings settings = newProcessTrace;
        if (settings != null) {
            apply(process, true, settings);
        }
    }

    /**
     * Install a call pattern for a function, module or all code. A null or
     * disabled pattern removes the entry.
     */
    public static void pattern(Term target, Term matchSpec, List flags) {
        Pattern pattern = pattern(matchSpec, flags);
        if (target instanceof Tuple && ((Tuple) target).arity() >= 3) {
            FunctionSignature signature = target.toFunctionSignature();
            if (pattern == null || !pattern.enabled()) {
                EXACT.remove(signature);
            } else {
                EXACT.put(signature, pattern);
            }
        } else if (target instanceof Atom) {
            Atom name = (Atom) target;
            if (name.equals(Atom.of("all")) || name.equals(Atom.of("existing"))
                || name.equals(Atom.of("new"))) {
                ALL = pattern;
                if (pattern == null || !pattern.enabled()) {
                    MODULES.clear();
                }
            } else if (pattern == null || !pattern.enabled()) {
                MODULES.remove(name);
            } else {
                MODULES.put(name, pattern);
            }
        } else {
            throw Error.badarg;
        }
        anyPattern = ALL != null || !MODULES.isEmpty() || !EXACT.isEmpty();
    }

    /**
     * `true` and a non-empty match-spec list enable tracing; `false` and the
     * empty list disable it. The match-spec body is not evaluated yet: every
     * call to an enabled function produces an event.
     */
    private static Pattern pattern(Term matchSpec, List flags) {
        boolean enabled;
        if (matchSpec.equals(Boolean.am_true)) {
            enabled = true;
        } else if (matchSpec.equals(Boolean.am_false)) {
            enabled = false;
        } else if (matchSpec.isList()) {
            enabled = matchSpec.toList().length() > 0;
        } else {
            throw Error.badarg;
        }
        boolean returnTrace = false;
        while (flags.length() > 0) {
            Term flag = flags.head();
            flags = flags.tail();
            if (!(flag instanceof Atom)) {
                throw Error.badarg;
            }
            switch (flag.toString()) {
            case "return_trace":
                returnTrace = true;
                break;
            case "local":
            case "global":
            case "meta":
            case "call_count":
            case "silent":
            case "exception_trace":
            case "seq_trace":
                break;
            default:
                throw Error.badarg;
            }
        }
        return new Pattern(enabled, returnTrace);
    }

    /**
     * Called before a function starts. Returns the pattern that matched, so
     * the caller can emit return_to after it returns.
     */
    public static Pattern beforeCall(Process process, FunctionSignature signature, Term args) {
        if (!anyPattern || !process.traceFlag(CALL)) {
            return null;
        }
        Pattern pattern = pattern(signature);
        if (pattern == null || !pattern.enabled()) {
            return null;
        }
        pattern.calls.incrementAndGet();
        process.trace(Tuple.of(TRACE, process.pid(), CALL,
            Tuple.of(signature.module(), signature.function(), args)));
        return pattern;
    }

    public static void afterCall(Process process, FunctionSignature signature, Pattern pattern) {
        if (pattern == null || !pattern.returnTrace()) {
            return;
        }
        process.trace(Tuple.of(TRACE, process.pid(), RETURN_TO,
            Tuple.of(signature.module(), signature.function(), signature.fun_arity())));
    }

    private static Pattern pattern(FunctionSignature signature) {
        Pattern pattern = EXACT.get(signature);
        if (pattern == null) {
            pattern = MODULES.get(signature.module());
        }
        if (pattern == null) {
            pattern = ALL;
        }
        return pattern;
    }

    /**
     * A send event is reported for the sender; the destination is the term
     * the message was addressed to.
     */
    public static void send(PID from, Term message, Term to) {
        if (isTraceMessage(message)) {
            return;
        }
        Process sender = resolve(from);
        if (sender == null || !sender.traceFlag(SEND)) {
            return;
        }
        sender.trace(Tuple.of(TRACE, sender.pid(), SEND, message, to));
    }

    /**
     * A receive event is reported after the message was removed from the
     * mailbox, which is the moment the receive clause selected it.
     */
    public static void receive(Process process, Term message) {
        if (isTraceMessage(message) || !process.traceFlag(RECEIVE)) {
            return;
        }
        process.trace(Tuple.of(TRACE, process.pid(), RECEIVE, message));
    }

    /**
     * Trace messages themselves are never traced. Without this a tracer with
     * the receive flag would trace the trace messages it reads and feed
     * itself forever.
     */
    public static boolean isTraceMessage(Term term) {
        return term instanceof Tuple && ((Tuple) term).arity() >= 3
            && ((Tuple) term).element(1).equals(TRACE);
    }

    private static Process resolve(PID pid) {
        ProcessOrPort process = ProcessRegistry.resolve(pid);
        return process instanceof Process ? (Process) process : null;
    }

}
