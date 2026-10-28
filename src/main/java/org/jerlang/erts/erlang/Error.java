package org.jerlang.erts.erlang;

import static org.jerlang.type.List.nil;

import org.jerlang.Process;
import org.jerlang.type.Atom;
import org.jerlang.type.List;
import org.jerlang.type.Str;
import org.jerlang.type.Term;
import org.jerlang.type.Tuple;

/**
 * Exception thrown by `erlang:error/1` and `erlang:error/2`, and by the
 * raise instructions.
 *
 * Erlang exceptions have a class (error, throw or exit), a reason and a
 * stacktrace. The class decides what `try ... catch Class:Reason:Stack` binds.
 */
public class Error extends RuntimeException {

    public static final Error badarg = new Error("badarg");

    private final Atom exceptionClass;
    private final Term reason;
    private final List args;

    // Captured when the exception reaches the interpreter, or passed in by
    // raw_raise/raise to preserve an existing stacktrace.
    private Term stacktrace;

    public Error(String reason) {
        this(Atom.of("error"), Str.of(reason), null, nil);
    }

    public Error(Term reason) {
        this(Atom.of("error"), reason, null, nil);
    }

    public Error(Term reason, List args) {
        this(Atom.of("error"), reason, null, args);
    }

    public Error(Atom exceptionClass, Term reason) {
        this(exceptionClass, reason, null, nil);
    }

    public Error(Atom exceptionClass, Term reason, Term stacktrace) {
        this(exceptionClass, reason, stacktrace, nil);
    }

    private Error(Atom exceptionClass, Term reason, Term stacktrace, List args) {
        this.exceptionClass = exceptionClass;
        this.reason = reason;
        this.stacktrace = stacktrace;
        this.args = args;
    }

    /**
     * Attach the current Erlang stack if this exception does not carry one.
     * Called by the interpreter at the raise, before any handler unwinds.
     */
    public void captureStacktrace(Process process) {
        if (stacktrace == null && process != null) {
            stacktrace = process.stacktrace();
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

    public List args() {
        return args;
    }

    @Override
    public String toString() {
        // TODO: Stack trace
        return Tuple.of(reason).toString();
    }

}
