package org.jerlang.type.stack;

import org.jerlang.Process;
import org.jerlang.erts.erlang.Error;
import org.jerlang.type.Term;

public class ExceptionHandler extends Term {

    private final Term label;
    private final int index;
    private final ExceptionHandler previous;
    private final int stackDepth;
    private final int cpDepth;
    private final int frameDepth;

    // The activation that installed this handler. Its catch label belongs to
    // that activation's instruction stream, so only that activation may use
    // it; every other Java frame has to let the exception unwind through.
    private final long activation;

    public ExceptionHandler(Term label, int index) {
        this(label, index, null, -1, -1, -1, 0);
    }

    public ExceptionHandler(Term label, int index, ExceptionHandler previous) {
        this(label, index, previous, -1, -1, -1, 0);
    }

    public ExceptionHandler(Term label, int index, ExceptionHandler previous,
        int stackDepth, int cpDepth, int frameDepth, long activation) {
        this.label = label;
        this.index = index;
        this.previous = previous;
        this.stackDepth = stackDepth;
        this.cpDepth = cpDepth;
        this.frameDepth = frameDepth;
        this.activation = activation;
    }

    /**
     * The activation identifier this handler was installed by.
     */
    public long activation() {
        return activation;
    }

    public void handle(Process process, Error error) {
        error.captureStacktrace(process);
        unwind(process);
        // The handler of the enclosing try becomes current again.
        process.setExceptionHandler(previous);
        process.setX(0, error.exceptionClass());
        process.setX(1, error.reason());
        process.setX(2, error.stacktrace());
    }

    /**
     * Drop the Erlang activations between the raise and this handler, so
     * that the stacktrace of the enclosing code does not contain abandoned
     * functions.
     */
    protected void unwindFrames(Process process) {
        if (frameDepth >= 0) {
            process.unwindFramesTo(frameDepth);
        }
    }

    /**
     * Drop the stack frames between the raise and this handler, so that the
     * catch clause runs in the frame that installed the handler.
     */
    protected void unwind(Process process) {
        unwindFrames(process);
        if (stackDepth >= 0) {
            process.unwindTo(stackDepth, cpDepth);
        }
    }

    public int index() {
        return index;
    }

    public Term label() {
        return label;
    }

    public ExceptionHandler previous() {
        return previous;
    }

    public ExceptionHandler toExceptionHandler() {
        return this;
    }

    @Override
    public String toString() {
        return "{exception_handler," + label + "}";
    }

}
