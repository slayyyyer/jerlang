package org.jerlang.erts;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.jerlang.Process;
import org.jerlang.ProcessSignals;
import org.jerlang.erts.dist.SpawnRequests;
import org.jerlang.erts.erlang.Error;
import org.jerlang.type.Atom;
import org.jerlang.type.Integer;
import org.jerlang.type.List;
import org.jerlang.type.PID;
import org.jerlang.type.Reference;
import org.jerlang.type.Term;
import org.jerlang.type.Tuple;
import org.jerlang.vm.VirtualMachine;

/**
 * The execute half and the process-local API of OTP's erpc.
 *
 * Remote calls use the SPAWN_REQUEST protocol: the peer starts
 * erpc:execute_call/4, which exits with a reply tuple, and a monitor
 * carries that tuple back to the caller. This is the same protocol OTP's
 * erpc module uses, so a real OTP node can call this node and this node
 * can call a real OTP node without a rex process.
 *
 * http://www.erlang.org/doc/man/erpc.html
 */
public class Erpc {

    private static final Atom ERPC = Atom.of("erpc");
    private static final Atom ERROR = Atom.of("error");
    private static final Atom EXIT = Atom.of("exit");
    private static final Atom RETURN = Atom.of("return");
    private static final Atom THROW = Atom.of("throw");
    private static final Atom SIGNAL = Atom.of("signal");
    private static final Atom NOCONNECTION = Atom.of("noconnection");
    private static final Atom TIMEOUT = Atom.of("timeout");
    private static final Atom REPLY = Atom.of("reply");
    private static final Atom ERROR_ONLY = Atom.of("error_only");
    private static final Atom NO = Atom.of("no");
    private static final Atom MONITOR = Atom.of("monitor");
    private static final Atom INFINITY = Atom.of("infinity");

    private Erpc() {
    }

    /**
     * Called by a process that a peer started with SPAWN_REQUEST. The
     * result is always reported by exiting with a reply tuple, because the
     * caller monitors the spawned process.
     */
    public static Term execute_call(Term reference, Term module, Term function, Term args) {
        Term reply;
        try {
            Term result = Erlang.apply(module, function, args);
            reply = Tuple.of(reference, RETURN, result);
        } catch (Error error) {
            reply = replyFor(reference, error);
        }
        // The monitor on the peer sees this as the DOWN reason. A normal
        // return would terminate with `normal` and lose the result.
        throw new Error(EXIT, reply);
    }

    private static Term replyFor(Term reference, Error error) {
        if (THROW.equals(error.exceptionClass())) {
            return Tuple.of(reference, THROW, error.reason());
        }
        if (EXIT.equals(error.exceptionClass())) {
            return Tuple.of(reference, EXIT, error.reason());
        }
        Term reason = error.reason();
        if (reason instanceof Tuple && ((Tuple) reason).arity() == 2
            && ERPC.equals(((Tuple) reason).element(1))) {
            return Tuple.of(reference, ERROR, reason);
        }
        return Tuple.of(reference, ERROR, reason, error.stacktrace());
    }

    /**
     * The no-reply half of erpc. Errors terminate the spawned process; the
     * caller is not monitoring it.
     */
    public static Term execute_cast(Term module, Term function, Term args) {
        return Erlang.apply(module, function, args);
    }

    /**
     * Call a function on a node, with erpc's result and exception
     * semantics: a returned term is returned, a throw is thrown, and an
     * exit or error becomes an exit/error with the remote reason.
     */
    public static Term call(Term node, Term module, Term function, Term args) {
        return call(node, module, function, args, INFINITY);
    }

    public static Term call(Term node, Term module, Term function, Term args, Term timeout) {
        validate(node, module, function, args);
        long timeoutMillis = timeoutMillis(timeout);
        Atom target = (Atom) node;
        if (target.equals(VirtualMachine.instance().node())) {
            return Erlang.apply(module, function, args);
        }
        return remoteCall(ProcessSignals.self(), target, (Atom) module, (Atom) function,
            args.toList(), timeoutMillis);
    }

    /**
     * Evaluate a function on a node without waiting for a reply.
     */
    public static Term cast(Term node, Term module, Term function, Term args) {
        validate(node, module, function, args);
        Process self = ProcessSignals.self();
        SpawnRequests.request(self.pid(), (Atom) node, (Atom) module, (Atom) function,
            args.toList(), List.of(Tuple.of(REPLY, NO)), null);
        return Atom.of("ok");
    }

    private static Term remoteCall(Process self, Atom node, Atom module, Atom function,
        List args, long timeoutMillis) {
        Reference resultReference = new Reference();
        CompletableFuture<Term> replyFuture = new CompletableFuture<>();
        Reference requestId = SpawnRequests.request(self.pid(), node, Atom.of("erpc"),
            Atom.of("execute_call"), List.of(resultReference, module, function, args),
            List.of(MONITOR), replyFuture);

        long deadline = timeoutMillis < 0 ? Long.MAX_VALUE
            : System.currentTimeMillis() + timeoutMillis;
        Term reply;
        try {
            if (timeoutMillis < 0) {
                reply = replyFuture.get();
            } else {
                reply = replyFuture.get(timeoutMillis, TimeUnit.MILLISECONDS);
            }
        } catch (TimeoutException e) {
            abandon(self, requestId);
            throw new Error(ERROR, Tuple.of(ERPC, TIMEOUT));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            abandon(self, requestId);
            throw new Error(ERROR, Tuple.of(ERPC, TIMEOUT));
        } catch (ExecutionException e) {
            abandon(self, requestId);
            throw new Error(ERROR, Tuple.of(ERPC, NOCONNECTION));
        }

        if (!(reply instanceof PID)) {
            // A failed spawn request can leave a DOWN noconnection behind
            // if the connection was lost after the monitor was set up.
            self.awaitMessage(message -> isDown(message, requestId), 0);
            throw new Error(ERROR, Tuple.of(ERPC, reply));
        }

        long remaining = deadline == Long.MAX_VALUE ? -1
            : Math.max(0, deadline - System.currentTimeMillis());
        Term down = self.awaitMessage(message -> isDown(message, requestId), remaining);
        if (down == null) {
            self.demonitor(requestId, true);
            abandon(self, requestId);
            throw new Error(ERROR, Tuple.of(ERPC, TIMEOUT));
        }
        return resultFromReason(down.toTuple().element(5), resultReference);
    }

    private static Term resultFromReason(Term reason, Reference resultReference) {
        if (reason instanceof Tuple) {
            Tuple tuple = (Tuple) reason;
            if (tuple.arity() >= 2 && resultReference.equals(tuple.element(1))) {
                Atom kind = tuple.element(2).toAtom();
                if (RETURN.equals(kind) && tuple.arity() == 3) {
                    return tuple.element(3);
                }
                if (THROW.equals(kind) && tuple.arity() == 3) {
                    throw new Error(THROW, tuple.element(3));
                }
                if (EXIT.equals(kind) && tuple.arity() == 3) {
                    throw new Error(EXIT, tuple.element(3));
                }
                if (ERROR.equals(kind)) {
                    if (tuple.arity() == 4) {
                        throw new Error(ERROR, tuple.element(3), tuple.element(4));
                    }
                    if (tuple.arity() == 3) {
                        throw new Error(ERROR, tuple.element(3));
                    }
                }
            }
        }
        if (NOCONNECTION.equals(reason)) {
            throw new Error(ERROR, Tuple.of(ERPC, NOCONNECTION));
        }
        throw new Error(EXIT, Tuple.of(SIGNAL, reason));
    }

    private static void abandon(Process self, Reference requestId) {
        SpawnRequests.abandon(self.pid(), requestId);
    }

    private static boolean isDown(Term message, Reference reference) {
        if (!(message instanceof Tuple)) {
            return false;
        }
        Tuple tuple = (Tuple) message;
        return tuple.arity() == 5
            && Atom.of("DOWN").equals(tuple.element(1))
            && reference.equals(tuple.element(2));
    }

    private static void validate(Term node, Term module, Term function, Term args) {
        if (!(node instanceof Atom) || !(module instanceof Atom)
            || !(function instanceof Atom) || !(args instanceof List)) {
            throw Error.badarg;
        }
    }

    /**
     * Parse a timeout as erpc accepts it: a non-negative integer in
     * milliseconds, or infinity. Returns -1 for infinity.
     */
    static long timeoutMillis(Term timeout) {
        if (INFINITY.equals(timeout)) {
            return -1;
        }
        if (!(timeout instanceof Integer)) {
            throw Error.badarg;
        }
        long value = ((Integer) timeout).toLong();
        if (value < 0) {
            throw Error.badarg;
        }
        return value;
    }

}
