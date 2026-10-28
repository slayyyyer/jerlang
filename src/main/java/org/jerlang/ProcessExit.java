package org.jerlang;

import org.jerlang.type.Term;

/**
 * Thrown inside a process that has a pending exit signal.
 *
 * This is deliberately not an {@link org.jerlang.erts.erlang.Error}: an exit
 * signal can not be caught by `try ... catch`, which is what makes
 * `exit(Pid, kill)` uncatchable.
 */
public class ProcessExit extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final Term reason;

    public ProcessExit() {
        this(null);
    }

    public ProcessExit(Term reason) {
        super(null, null, false, false);
        this.reason = reason;
    }

    public Term reason() {
        return reason;
    }

}
