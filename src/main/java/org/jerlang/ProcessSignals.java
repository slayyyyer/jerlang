package org.jerlang;

import java.util.Map;

import org.jerlang.erts.dist.Distribution;
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
    private static final Atom EXIT = Atom.of("exit");

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
        for (Map.Entry<Reference, Process.Monitor> monitor : termination.monitoredBy.entrySet()) {
            Process.Monitor watchedBy = monitor.getValue();
            if (Process.onThisNode(watchedBy.watcher)) {
                Process watcher = resolve(watchedBy.watcher);
                if (watcher != null) {
                    Term object = watchedBy.name != null ? watchedBy.name : termination.pid;
                    watcher.send(Tuple.of(DOWN, monitor.getKey(), PROCESS,
                        object, termination.reason));
                }
            } else {
                // A remote monitor reports the name it watched, if any, so
                // that the peer can reconstruct the {Name, Node} object.
                Distribution.sendMonitorExit(
                    watchedBy.name != null ? watchedBy.name : termination.pid,
                    watchedBy.watcher, monitor.getKey(), termination.reason);
            }
        }
        for (PID link : termination.links) {
            Process peer = resolve(link);
            if (peer != null) {
                peer.exitSignal(termination.reason, termination.pid);
            } else if (!Process.onThisNode(link)) {
                Distribution.sendExit(termination.pid, link, termination.reason, false);
            }
        }
        for (Map.Entry<Reference, Term> monitor : termination.monitors.entrySet()) {
            Term target = monitor.getValue();
            if (target instanceof PID) {
                PID pid = (PID) target;
                Process peer = resolve(pid);
                if (peer != null) {
                    peer.removeMonitoredBy(monitor.getKey());
                } else if (!Process.onThisNode(pid)) {
                    Distribution.sendDemonitor(termination.pid, pid, pid.node(), monitor.getKey());
                }
            } else if (target instanceof Tuple) {
                Tuple nameAndNode = (Tuple) target;
                if (nameAndNode.arity() == 2 && nameAndNode.element(2) instanceof Atom) {
                    Distribution.sendDemonitor(termination.pid, nameAndNode.element(1),
                        nameAndNode.element(2).toAtom(), monitor.getKey());
                }
            }
        }
        ProcessRegistry.remove(termination.pid);
    }

    /**
     * The Erlang reason an uncaught exception terminates a process with.
     *
     * Erlang/OTP makes the reason of an uncaught error or throw carry the
     * stacktrace captured at the raise, and every link and monitor sees
     * that same tuple. An uncaught throw is reported as
     * {{nocatch, Value}, Stacktrace}; exit/1 and a killed process keep
     * their plain reason.
     */
    public static Term reason(Throwable failure) {
        if (failure instanceof Error) {
            Error error = (Error) failure;
            Term reason = THROW.equals(error.exceptionClass())
                ? Tuple.of(Atom.of("nocatch"), error.reason())
                : error.reason();
            if (EXIT.equals(error.exceptionClass())) {
                return reason;
            }
            return Tuple.of(reason, error.stacktrace());
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
