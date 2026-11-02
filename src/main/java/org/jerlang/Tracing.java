package org.jerlang;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

import org.jerlang.erts.erlang.Error;
import org.jerlang.stdlib.ets.MatchSpec;
import org.jerlang.type.Atom;
import org.jerlang.type.Boolean;
import org.jerlang.type.ImproperList;
import org.jerlang.type.Integer;
import org.jerlang.type.List;
import org.jerlang.type.PID;
import org.jerlang.type.Str;
import org.jerlang.type.Term;
import org.jerlang.type.Tuple;

/**
 * erlang:trace/3 and erlang:trace_pattern/3.
 *
 * A process carries the flags it was traced with, and a global pattern table
 * says which functions produce call events. A trace event is an ordinary
 * message to the tracer, so the runtime needs no separate event loop.
 *
 * The match-spec body is evaluated as a list of actions. `{message, Term}`
 * replaces the event payload, `{return_trace}` and `{exception_trace}` select
 * return and exception events for that call, `{silent, Bool}` changes the
 * process trace mode, and the trace-flag actions update the current process.
 */
public final class Tracing {

    public static final Atom TRACE = Atom.of("trace");
    public static final Atom CALL = Atom.of("call");
    public static final Atom RETURN_TO = Atom.of("return_to");
    public static final Atom RETURN_FROM = Atom.of("return_from");
    public static final Atom EXCEPTION_FROM = Atom.of("exception_from");
    public static final Atom SEND = Atom.of("send");
    public static final Atom RECEIVE = Atom.of("receive");
    public static final Atom TRACER = Atom.of("tracer");
    public static final Atom SILENT = Atom.of("silent");

    private static final Set<Atom> FLAGS = ConcurrentHashMap.newKeySet();
    private static final Map<FunctionSignature, Pattern> EXACT = new ConcurrentHashMap<>();
    private static final Map<Atom, Pattern> MODULES = new ConcurrentHashMap<>();
    private static volatile Pattern ALL = null;
    private static volatile boolean anyPattern = false;
    private static volatile Settings newProcessTrace = null;

    /** The node's trace control word, read and written by match-spec actions. */
    private static final AtomicLong TRACE_CONTROL_WORD = new AtomicLong();

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
        private final MatchSpec matchSpec;
        private final AtomicLong calls = new AtomicLong();

        private Pattern(boolean enabled, boolean returnTrace, MatchSpec matchSpec) {
            this.enabled = enabled;
            this.returnTrace = returnTrace;
            this.matchSpec = matchSpec;
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
     * The per-call result of evaluating a trace match spec. A trace message
     * is sent before the call; the return and exception flags decide what is
     * sent when the activation ends.
     */
    public static final class Decision {

        private final FunctionSignature signature;
        private Term message;
        private boolean suppress;
        private boolean returnTrace;
        private boolean exceptionTrace;

        private Decision(FunctionSignature signature) {
            this.signature = signature;
        }

        public FunctionSignature signature() {
            return signature;
        }

        public boolean returnTrace() {
            return returnTrace;
        }

        public boolean exceptionTrace() {
            return exceptionTrace;
        }

        public boolean suppress() {
            return suppress;
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
     * empty list disable it. A non-empty body is evaluated as an action list.
     */
    private static Pattern pattern(Term matchSpec, List flags) {
        boolean enabled;
        MatchSpec compiled = null;
        if (matchSpec.equals(Boolean.am_true)) {
            enabled = true;
        } else if (matchSpec.equals(Boolean.am_false)) {
            enabled = false;
        } else if (matchSpec.isList()) {
            enabled = matchSpec.toList().length() > 0;
            if (enabled) {
                compiled = MatchSpec.compile(matchSpec);
            }
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
        return new Pattern(enabled, returnTrace, compiled);
    }

    /**
     * Called before a function starts. Returns the pattern that matched, so
     * the caller can emit return_to after it returns.
     *
     * @deprecated use {@link #beforeCallDecision(Process, FunctionSignature, Term)}
     *             for trace body actions and return_from/exception_from values.
     */
    @Deprecated
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

    /**
     * Emit the legacy return_to event for a pattern returned by
     * {@link #beforeCall(Process, FunctionSignature, Term)}.
     *
     * @deprecated use {@link #afterCall(Process, FunctionSignature, Term, Throwable)}
     *             for return_from and exception_from events.
     */
    @Deprecated
    public static void afterCall(Process process, FunctionSignature signature, Pattern pattern) {
        if (pattern == null || !pattern.returnTrace()) {
            return;
        }
        process.trace(Tuple.of(TRACE, process.pid(), RETURN_TO,
            Tuple.of(signature.module(), signature.function(), signature.fun_arity())));
    }

    /**
     * Called before a function starts. Returns the per-call decision, so the
     * return path knows which actions the matching clause selected.
     */
    public static Decision beforeCallDecision(Process process, FunctionSignature signature, Term args) {
        if (!anyPattern || !process.traceFlag(CALL)) {
            return null;
        }
        Pattern pattern = pattern(signature);
        if (pattern == null || !pattern.enabled()) {
            return null;
        }
        Decision decision = new Decision(signature);
        decision.returnTrace = pattern.returnTrace;
        if (pattern.matchSpec != null) {
            boolean matched = pattern.matchSpec.traceActions(args,
                action -> applyAction(process, decision, action));
            if (!matched) {
                return null;
            }
        }
        pattern.calls.incrementAndGet();
        process.pushTracedCall(signature, decision);
        if (!decision.suppress && !process.traceFlag(SILENT)) {
            Term message = decision.message != null ? decision.message
                : Tuple.of(TRACE, process.pid(), CALL,
                    Tuple.of(signature.module(), signature.function(), args));
            process.trace(message);
        }
        return decision;
    }

    /**
     * The matching body actions. A trace body is an action list, but a single
     * action tuple is accepted for the older form this runtime used.
     */
    private static void applyAction(Process process, Decision decision, Term action) {
        if (action instanceof Atom) {
            if (Atom.of("return_trace").equals(action)) {
                decision.returnTrace = true;
            } else if (Atom.of("exception_trace").equals(action)) {
                decision.returnTrace = true;
                decision.exceptionTrace = true;
            }
            return;
        }
        if (!(action instanceof Tuple) || ((Tuple) action).arity() == 0) {
            return;
        }
        Tuple call = (Tuple) action;
        if (!(call.element(1) instanceof Atom)) {
            return;
        }
        String name = call.element(1).toString();
        switch (name) {
        case "message":
            if (call.arity() == 2) {
                Term value = evaluateTerm(process, call.element(2));
                if (Boolean.am_false.equals(value)) {
                    decision.suppress = true;
                } else if (Boolean.am_true.equals(value)) {
                    decision.suppress = false;
                    decision.message = null;
                } else {
                    decision.message = value;
                }
            }
            break;
        case "return_trace":
            decision.returnTrace = true;
            break;
        case "exception_trace":
            decision.returnTrace = true;
            decision.exceptionTrace = true;
            break;
        case "silent":
            if (call.arity() == 2) {
                Term value = call.element(2);
                if (Boolean.am_true.equals(value) || Boolean.am_false.equals(value)) {
                    process.setTraceFlag(SILENT, Boolean.am_true.equals(value));
                }
            }
            break;
        case "display":
            if (call.arity() == 2) {
                System.out.println(evaluateTerm(process, call.element(2)));
            }
            break;
        case "enable_trace":
            if (call.arity() == 2) {
                applyTraceFlags(process, call.element(2), true);
            }
            break;
        case "disable_trace":
            if (call.arity() == 2) {
                applyTraceFlags(process, call.element(2), false);
            }
            break;
        case "trace":
            if (call.arity() == 3) {
                applyTraceFlags(process, call.element(2), false);
                applyTraceFlags(process, call.element(3), true);
            }
            break;
        case "set_tcw":
            if (call.arity() == 2 && call.element(2) instanceof Integer) {
                TRACE_CONTROL_WORD.set(((Integer) call.element(2)).toLong());
            }
            break;
        default:
            // caller, caller_line, current_stacktrace, process_dump,
            // get_seq_token and set_seq_token are meaningful only when their
            // return value is used by another action. They are evaluated by
            // evaluateTerm where possible; a bare action is a no-op.
            break;
        }
    }

    private static void applyTraceFlags(Process process, Term flags, boolean enable) {
        if (flags instanceof Atom) {
            process.setTraceFlag((Atom) flags, enable);
        } else if (flags instanceof List) {
            List list = (List) flags;
            while (list.length() > 0) {
                if (list.head() instanceof Atom) {
                    process.setTraceFlag((Atom) list.head(), enable);
                }
                list = list.tail();
            }
        }
    }

    /**
     * Evaluate the action expressions that produce a value: caller,
     * current_stacktrace, process_dump, get_tcw and set_tcw. Everything else
     * is rebuilt with the same shape so a message can contain those values.
     */
    private static Term evaluateTerm(Process process, Term expression) {
        if (expression instanceof Tuple) {
            Tuple tuple = (Tuple) expression;
            if (tuple.arity() == 1 && tuple.element(1) instanceof Atom) {
                String name = tuple.element(1).toString();
                switch (name) {
                case "caller":
                    return caller(process);
                case "caller_line":
                    FunctionSignature caller = callerSignature(process);
                    return caller == null ? Atom.of("undefined")
                        : Tuple.of(caller.module(), caller.function(),
                            caller.fun_arity(), Atom.of("undefined"));
                case "current_stacktrace":
                    return process.stacktrace();
                case "process_dump":
                    return Str.of(process.toString());
                case "get_tcw":
                    return new Integer(java.math.BigInteger.valueOf(TRACE_CONTROL_WORD.get()));
                default:
                    return expression;
                }
            }
            Tuple result = new Tuple(tuple.arity());
            for (int index = 1; index <= tuple.arity(); index++) {
                result.set(index, evaluateTerm(process, tuple.element(index)));
            }
            return result;
        }
        if (expression instanceof List) {
            List list = (List) expression;
            java.util.List<Term> elements = new java.util.ArrayList<>();
            while (list.length() > 0) {
                elements.add(evaluateTerm(process, list.head()));
                list = list.tail();
            }
            Term result = List.nil;
            for (int index = elements.size() - 1; index >= 0; index--) {
                result = new List(elements.get(index), (List) result);
            }
            return result;
        }
        if (expression instanceof ImproperList) {
            ImproperList list = (ImproperList) expression;
            return new ImproperList(evaluateTerm(process, list.head()),
                evaluateTerm(process, list.tail()));
        }
        return expression;
    }

    private static FunctionSignature callerSignature(Process process) {
        FunctionSignature signature = process.signature();
        return signature;
    }

    private static Term caller(Process process) {
        FunctionSignature caller = callerSignature(process);
        return caller == null ? Atom.of("undefined")
            : Tuple.of(caller.module(), caller.function(), caller.fun_arity());
    }

    public static void afterCall(Process process, FunctionSignature signature, Term result,
        Throwable failure) {
        Decision decision = process.popTracedCall(signature);
        if (decision == null) {
            return;
        }
        if (failure != null) {
            if (decision.exceptionTrace && !process.traceFlag(SILENT)) {
                process.trace(Tuple.of(TRACE, process.pid(), EXCEPTION_FROM,
                    Tuple.of(signature.module(), signature.function(), signature.fun_arity()),
                    exceptionReason(failure)));
            }
        } else if (decision.returnTrace && !decision.suppress
            && !process.traceFlag(SILENT)) {
            process.trace(Tuple.of(TRACE, process.pid(), RETURN_FROM,
                Tuple.of(signature.module(), signature.function(), signature.fun_arity()),
                result));
        }
        if (process.traceFlag(RETURN_TO) && !decision.suppress) {
            process.trace(Tuple.of(TRACE, process.pid(), RETURN_TO,
                Tuple.of(signature.module(), signature.function(), signature.fun_arity())));
        }
    }

    /**
     * A local return has no Java caller to run afterCall. The frame about to
     * be popped names the function, and X0 holds its result.
     */
    public static void afterReturn(Process process, FunctionSignature signature, Term result) {
        Decision decision = process.popTracedCall(signature);
        if (decision == null) {
            return;
        }
        if (decision.returnTrace && !decision.suppress && !process.traceFlag(SILENT)) {
            process.trace(Tuple.of(TRACE, process.pid(), RETURN_FROM,
                Tuple.of(signature.module(), signature.function(), signature.fun_arity()),
                result));
        }
        if (process.traceFlag(RETURN_TO) && !decision.suppress) {
            process.trace(Tuple.of(TRACE, process.pid(), RETURN_TO,
                Tuple.of(signature.module(), signature.function(), signature.fun_arity())));
        }
    }

    private static Term exceptionReason(Throwable failure) {
        if (failure instanceof Error) {
            Error error = (Error) failure;
            return Tuple.of(error.exceptionClass(), error.reason());
        }
        return Tuple.of(Atom.of("error"), Str.of(String.valueOf(failure)));
    }

    /** Clear the global pattern table; used by tests and by a fresh node. */
    public static void reset() {
        EXACT.clear();
        MODULES.clear();
        ALL = null;
        anyPattern = false;
        newProcessTrace = null;
        TRACE_CONTROL_WORD.set(0);
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
