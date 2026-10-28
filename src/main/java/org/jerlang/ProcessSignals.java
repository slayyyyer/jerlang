package org.jerlang;

import java.util.Map;

import org.jerlang.erts.erlang.Error;
import org.jerlang.type.Atom;
import org.jerlang.type.PID;
import org.jerlang.type.Reference;
import org.jerlang.type.Str;
import org.jerlang.type.Term;
import org.jerlang.type.Tuple;

/**
 * The notifications a terminating process leaves behind.
 *
 * A process is terminated exactly once, by the scheduler that ran it. The
 * exit reason then goes to the processes linked to it and to the processes
 * monitoring it, which is what makes links and monitors visible from Erlang.
 */
public final class ProcessSignals {

    private static final Atom DOWN = Atom.of("DOWN");
    private static final Atom PROCESS = Atom.of("process");
    private static final Atom THROW = Atom.of("throw");

    private ProcessSignals() {
    }

    /**
     * The process running on the current thread, for the signal BIFs.
     */
    public static Process self() {
        ProcessOrPort self = ProcessRegistry.self();
        if (!(self instanceof Process)) {
            throw Error.badarg;
        }
        return (Process) self;
    }

    /**
     * Finish a process and tell every linked process and monitor about it.
     * A process only terminates once, so a second call does nothing.
     */
    public static void terminate(Process process, Term reason) {
        Process.Termination termination = process.terminate(reason);
        if (termination == null) {
            return;
        }
        for (Map.Entry<Reference, PID> monitor : termination.monitoredBy.entrySet()) {
            Process watcher = resolve(monitor.getValue());
            if (watcher != null) {
                watcher.send(Tuple.of(DOWN, monitor.getKey(), PROCESS,
                    termination.pid, termination.reason));
            }
        }
        for (PID link : termination.links) {
            Process peer = resolve(link);
            if (peer != null) {
                peer.exitSignal(termination.reason, termination.pid);
            }
        }
        for (Map.Entry<Reference, PID> monitor : termination.monitors.entrySet()) {
            Process target = resolve(monitor.getValue());
            if (target != null) {
                target.removeMonitoredBy(monitor.getKey());
            }
        }
        ProcessRegistry.remove(termination.pid);
    }

    /**
     * The Erlang reason an uncaught exception terminates a process with.
     */
    public static Term reason(Throwable failure) {
        if (failure instanceof Error) {
            Error error = (Error) failure;
            if (THROW.equals(error.exceptionClass())) {
                // An uncaught throw is reported as {nocatch, Value}, the way
                // Erlang/OTP reports it.
                return Tuple.of(Atom.of("nocatch"), error.reason());
            }
            return error.reason();
        }
        if (failure instanceof ProcessExit) {
            ProcessExit exit = (ProcessExit) failure;
            return exit.reason() == null ? Atom.of("killed") : exit.reason();
        }
        return Tuple.of(Atom.of("error"), Str.of(String.valueOf(failure)));
    }

    private static Process resolve(PID pid) {
        ProcessOrPort process = ProcessRegistry.resolve(pid);
        return process instanceof Process ? (Process) process : null;
    }

}
