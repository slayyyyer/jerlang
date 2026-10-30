package org.jerlang.type.stack;

import org.jerlang.Process;
import org.jerlang.erts.erlang.Error;
import org.jerlang.type.Atom;
import org.jerlang.type.Term;
import org.jerlang.type.Tuple;

public class CatchExceptionHandler extends ExceptionHandler {

    public CatchExceptionHandler(Term label) {
        this(label, null, -1, -1, -1, 0);
    }

    public CatchExceptionHandler(Term label, ExceptionHandler previous) {
        this(label, previous, -1, -1, -1, 0);
    }

    public CatchExceptionHandler(Term label, ExceptionHandler previous,
        int stackDepth, int cpDepth, int frameDepth, long activation) {
        super(label, -1, previous, stackDepth, cpDepth, frameDepth, activation);
    }

    /**
     * A `catch` expression has no handler code of its own: execution
     * continues after the caught call, so the current frame and the
     * continuation pointer must be left alone.
     */
    @Override
    public void handle(Process process, Error error) {
        error.captureStacktrace(process);
        unwindFrames(process);
        process.setExceptionHandler(previous());
        process.setX(0, Tuple.of(Atom.of("EXIT"), error.reason()));
    }

    @Override
    public String toString() {
        return "{catch_exception_handler," + label() + "}";
    }

}
