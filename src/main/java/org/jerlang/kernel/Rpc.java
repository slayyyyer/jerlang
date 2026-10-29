package org.jerlang.kernel;

import org.jerlang.erts.Erpc;
import org.jerlang.erts.dist.Distribution;
import org.jerlang.erts.erlang.Error;
import org.jerlang.stdlib.Lists;
import org.jerlang.type.Atom;
import org.jerlang.type.Integer;
import org.jerlang.type.List;
import org.jerlang.type.Term;
import org.jerlang.type.Tuple;
import org.jerlang.vm.VirtualMachine;

/**
 * Remote procedure calls. The wire protocol is the erpc spawn/monitor
 * protocol, not the old rex gen_server protocol.
 *
 * http://www.erlang.org/doc/man/rpc.html
 */
public class Rpc {

    private static final Atom BADRPC = Atom.of("badrpc");
    private static final Atom EXIT = Atom.of("EXIT");
    private static final Atom ERPC = Atom.of("erpc");
    private static final Atom NODEDOWN = Atom.of("nodedown");
    private static final Atom NOCONNECTION = Atom.of("noconnection");
    private static final Atom TIMEOUT = Atom.of("timeout");
    private static final Atom NOTSUP = Atom.of("notsup");
    private static final Atom INFINITY = Atom.of("infinity");
    private static final Atom ABRCAST = Atom.of("abcast");
    private static final Atom TRUE = Atom.of("true");

    private Rpc() {
    }

    public static Term call(Term node, Term module, Term function, Term args) {
        return call(node, module, function, args, INFINITY);
    }

    /**
     * Call a function on another node, converting failures to
     * `{badrpc, Reason}` as rpc:call/4,5 documents.
     */
    public static Term call(Term node, Term module, Term function, Term args, Term timeout) {
        validate(node, module, function, args);
        try {
            return rpcResult(Erpc.call(node, module, function, args, timeout));
        } catch (Error error) {
            return rpcError(error);
        }
    }

    public static Term block_call(Term node, Term module, Term function, Term args) {
        return call(node, module, function, args);
    }

    /**
     * JErlang has no global serialization lock, so block_call behaves like
     * call; the remote operation still runs on one node at a time only if
     * the application provides that guarantee.
     */
    public static Term block_call(Term node, Term module, Term function, Term args, Term timeout) {
        return call(node, module, function, args, timeout);
    }

    public static Term cast(Term node, Term module, Term function, Term args) {
        validate(node, module, function, args);
        Erpc.cast(node, module, function, args);
        return TRUE;
    }

    public static Term multicall(Term nodes, Term module, Term function, Term args) {
        return multicall(nodes, module, function, args, INFINITY);
    }

    public static Term multicall(Term nodes, Term module, Term function, Term args, Term timeout) {
        if (!(nodes instanceof List)) {
            throw Error.badarg;
        }
        validate(module, function, args);
        java.util.ArrayList<Term> replies = new java.util.ArrayList<>();
        java.util.ArrayList<Term> badNodes = new java.util.ArrayList<>();
        List rest = nodes.toList();
        while (rest.length() > 0) {
            Term node = rest.head();
            if (!(node instanceof Atom)) {
                throw Error.badarg;
            }
            Term result = call(node, module, function, args, timeout);
            if (result instanceof Tuple && ((Tuple) result).arity() == 2
                && BADRPC.equals(((Tuple) result).element(1))) {
                badNodes.add(node);
            } else {
                replies.add(result);
            }
            rest = rest.tail();
        }
        return Tuple.of(List.of(replies), List.of(badNodes));
    }

    public static Term multicall(Term module, Term function, Term args) {
        List nodes = new List(VirtualMachine.instance().node(), Distribution.nodes());
        return multicall(nodes, module, function, args, INFINITY);
    }

    /**
     * Evaluate on every listed node without collecting results.
     */
    public static Term eval_everywhere(Term nodes, Term module, Term function, Term args) {
        if (!(nodes instanceof List)) {
            throw Error.badarg;
        }
        List rest = nodes.toList();
        while (rest.length() > 0) {
            cast(rest.head(), module, function, args);
            rest = rest.tail();
        }
        return ABRCAST;
    }

    /**
     * Asynchronously send a message to a registered name on every node.
     */
    public static Term abcast(Term nodes, Term name, Term message) {
        if (!(nodes instanceof List) || !(name instanceof Atom)) {
            throw Error.badarg;
        }
        List rest = nodes.toList();
        while (rest.length() > 0) {
            if (rest.head() instanceof Atom) {
                Distribution.send(Tuple.of(name, rest.head()), null, message);
            }
            rest = rest.tail();
        }
        return ABRCAST;
    }

    private static Term rpcResult(Term result) {
        if (isExitTuple(result)) {
            return Tuple.of(BADRPC, Tuple.of(EXIT, result));
        }
        return result;
    }

    private static Term rpcError(Error error) {
        Atom exceptionClass = error.exceptionClass();
        if (Atom.of("throw").equals(exceptionClass)) {
            Term reason = error.reason();
            return isExitTuple(reason)
                ? Tuple.of(BADRPC, Tuple.of(EXIT, reason))
                : reason;
        }
        if (Atom.of("exit").equals(exceptionClass)) {
            return Tuple.of(BADRPC, Tuple.of(EXIT, error.reason()));
        }

        Term reason = error.reason();
        if (reason instanceof Tuple && ((Tuple) reason).arity() == 2
            && ERPC.equals(((Tuple) reason).element(1))) {
            Term detail = ((Tuple) reason).element(2);
            if (NODEDOWN.equals(detail) || NOCONNECTION.equals(detail)) {
                return Tuple.of(BADRPC, NODEDOWN);
            }
            if (TIMEOUT.equals(detail)) {
                return Tuple.of(BADRPC, TIMEOUT);
            }
            if (NOTSUP.equals(detail)) {
                return Tuple.of(BADRPC, NOTSUP);
            }
        }
        return Tuple.of(BADRPC, Tuple.of(EXIT, Tuple.of(reason, error.stacktrace())));
    }

    private static boolean isExitTuple(Term result) {
        return result instanceof Tuple
            && ((Tuple) result).arity() == 2
            && EXIT.equals(((Tuple) result).element(1));
    }

    private static void validate(Term node, Term module, Term function, Term args) {
        if (!(node instanceof Atom) || !(module instanceof Atom)
            || !(function instanceof Atom) || !(args instanceof List)) {
            throw Error.badarg;
        }
    }

    private static void validate(Term module, Term function, Term args) {
        if (!(module instanceof Atom) || !(function instanceof Atom)
            || !(args instanceof List)) {
            throw Error.badarg;
        }
    }

    /**
     * The documented default for rpc:call is infinity, and an integer
     * timeout is in milliseconds.
     */
    static long timeout(Term timeout) {
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
