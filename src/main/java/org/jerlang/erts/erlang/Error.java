package org.jerlang.erts.erlang;

import static org.jerlang.type.List.nil;

import org.jerlang.Process;
import org.jerlang.type.Atom;
import org.jerlang.type.List;
import org.jerlang.type.Str;
import org.jerlang.type.Term;
import org.jerlang.type.Tuple;

/**
 * Exception thrown by `erlang:error/1,2,3`, and by the raise instructions.
 *
 * Erlang exceptions have a class (error, throw or exit), a reason and a
 * stacktrace. The class decides what `try ... catch Class:Reason:Stack` binds.
 *
 * An error raised by `error/2` or `error/3` carries the caller's argument
 * list, which replaces the arity in the youngest stack frame. An `error_info`
 * option is kept for the crash report, which is the only consumer of it.
 */
public class Error extends RuntimeException {

    // badarg is an atom in Erlang, not a string: catch error:badarg has to
    // match it.
    public static final Error badarg = new Error(Atom.of("badarg"));

    private final Atom exceptionClass;
    private final Term reason;
    private final List args;
    private final Term errorInfo;

    // Captured when the exception reaches the interpreter, or passed in by
    // raw_raise/raise to preserve an existing stacktrace.
    private Term stacktrace;

    public Error(String reason) {
        this(Atom.of("error"), Str.of(reason), null, null, null);
    }

    public Error(Term reason) {
        this(Atom.of("error"), reason, null, null, null);
    }

    public Error(Term reason, List args) {
        this(Atom.of("error"), reason, null, args, null);
    }

    public Error(Term reason, List args, Term errorInfo) {
        this(Atom.of("error"), reason, null, args, errorInfo);
    }

    public Error(Atom exceptionClass, Term reason) {
        this(exceptionClass, reason, null, null, null);
    }

    public Error(Atom exceptionClass, Term reason, Term stacktrace) {
        this(exceptionClass, reason, stacktrace, null, null);
    }

    private Error(Atom exceptionClass, Term reason, Term stacktrace, List args, Term errorInfo) {
        this.exceptionClass = exceptionClass;
        this.reason = reason;
        this.stacktrace = stacktrace;
        this.args = args;
        this.errorInfo = errorInfo;
    }

    /**
     * Attach the current Erlang stack if this exception does not carry one.
     * Called by the interpreter at the raise, before any handler unwinds.
     */
    public void captureStacktrace(Process process) {
        if (stacktrace == null && process != null) {
            stacktrace = process.stacktrace(hasArgs() ? args : null);
        }
    }

    public Atom exceptionClass() {
        return exceptionClass;
    }

    public Term reason() {
        return reason;
    }

    public Term stacktrace() {
        return stacktrace == null ? nil : stacktrace;
    }

    /**
     * The caller's argument list, or an empty list when the error was not
     * raised by error/2,3. Use hasArgs to tell the two apart.
     */
    public List args() {
        return args == null ? nil : args;
    }

    /**
     * Whether the stacktrace's youngest frame carries an argument list.
     */
    public boolean hasArgs() {
        return args != null;
    }

    /**
     * The `error_info` option of error/3, or null when it was not given. The
     * crash report is the only thing that reads it.
     */
    public Term errorInfo() {
        return errorInfo;
    }

    @Override
    public String toString() {
        // TODO: Stack trace
        return Tuple.of(reason).toString();
    }

}
