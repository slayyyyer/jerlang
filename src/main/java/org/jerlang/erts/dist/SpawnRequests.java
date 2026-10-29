package org.jerlang.erts.dist;

import java.util.ArrayList;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

import org.jerlang.Process;
import org.jerlang.ProcessOrPort;
import org.jerlang.ProcessRegistry;
import org.jerlang.type.Atom;
import org.jerlang.type.Fun;
import org.jerlang.type.List;
import org.jerlang.type.PID;
import org.jerlang.type.Reference;
import org.jerlang.type.Term;
import org.jerlang.type.Tuple;
import org.jerlang.vm.VirtualMachine;

/**
 * The spawn half of the distribution protocol: `erlang:spawn_request/5`
 * sends a SPAWN_REQUEST and remembers the request until the peer answers
 * with a SPAWN_REPLY, and an incoming SPAWN_REQUEST starts a process on
 * this node.
 *
 * The request identifier is the reference the caller receives from
 * `spawn_request/5`. It is also the monitor reference when the `monitor`
 * option is used, exactly as Erlang/OTP documents.
 *
 * See:
 * http://www.erlang.org/doc/apps/erts/erl_dist_protocol.html#SPAWN_REQUEST
 */
public final class SpawnRequests {

    /** Send a spawn reply message whether the operation succeeds or not. */
    public static final int REPLY_YES = 0;

    /** Send no spawn reply messages at all. */
    public static final int REPLY_NO = 1;

    /** Only send a spawn reply message when the operation fails. */
    public static final int REPLY_ERROR_ONLY = 2;

    /** Only send a spawn reply message when the operation succeeds. */
    public static final int REPLY_SUCCESS_ONLY = 3;

    private static final Atom ERLANG = Atom.of("erlang");
    private static final Atom APPLY = Atom.of("apply");
    private static final Atom LINK = Atom.of("link");
    private static final Atom MONITOR = Atom.of("monitor");
    private static final Atom REPLY = Atom.of("reply");
    private static final Atom REPLY_TAG = Atom.of("reply_tag");
    private static final Atom SPAWN_REPLY = Atom.of("spawn_reply");
    private static final Atom OK = Atom.of("ok");
    private static final Atom ERROR = Atom.of("error");
    private static final Atom YES = Atom.of("yes");
    private static final Atom NO = Atom.of("no");
    private static final Atom ERROR_ONLY = Atom.of("error_only");
    private static final Atom SUCCESS_ONLY = Atom.of("success_only");
    private static final Atom BADARG = Atom.of("badarg");
    private static final Atom BADOPT = Atom.of("badopt");
    private static final Atom NOTSUP = Atom.of("notsup");
    private static final Atom NOCONNECTION = Atom.of("noconnection");
    private static final Atom SYSTEM_LIMIT = Atom.of("system_limit");

    private static final Map<Reference, Pending> PENDING = new ConcurrentHashMap<>();

    private SpawnRequests() {
    }

    /**
     * Send a spawn request. `blocking` is completed with the PID of the
     * new process, or with the error reason when the spawn fails; it is
     * null for `spawn_request/5`, which reports through a message instead.
     * Returns the request identifier.
     */
    public static Reference request(PID from, Atom node, Atom module, Atom function,
        List args, List options, CompletableFuture<Term> blocking) {
        Reference requestId = new Reference();
        Options parsed = new Options();
        if (!parsed.parse(options)) {
            fail(requestId, from, node, blocking, BADOPT, SPAWN_REPLY, REPLY_YES);
            return requestId;
        }
        if (node.equals(VirtualMachine.instance().node())) {
            spawnLocal(requestId, from, module, function, args, parsed);
            return requestId;
        }
        Pending pending = new Pending(from, node, parsed.replyTag, parsed.replyMode, blocking);
        PENDING.put(requestId, pending);
        Term failure = Distribution.sendSpawnRequest(from, node, requestId, module, function,
            args, parsed.forwarded());
        if (failure != null) {
            PENDING.remove(requestId);
            fail(requestId, from, node, blocking, failure, parsed.replyTag, parsed.replyMode);
        }
        return requestId;
    }

    /**
     * Give up a request before its SPAWN_REPLY arrives. Returns true when
     * the request was still pending.
     */
    public static Term abandon(PID from, Reference requestId) {
        Pending pending = PENDING.get(requestId);
        if (pending == null || !pending.from.equals(from)) {
            return Atom.of("false");
        }
        if (!PENDING.remove(requestId, pending)) {
            return Atom.of("false");
        }
        if (pending.blocking != null) {
            pending.blocking.cancel(false);
        }
        return Atom.of("true");
    }

    /**
     * A SPAWN_REPLY arrived. The flags say whether the peer set up a link
     * or a monitor for the new process; both are recorded before the reply
     * message is delivered, so that a signal that follows the reply finds
     * the state it belongs to.
     */
    static void replyReceived(Atom peer, Reference requestId, PID to, int flags, Term result) {
        Pending pending = PENDING.remove(requestId);
        if (pending == null || !pending.node.equals(peer)) {
            return;
        }
        if (result instanceof PID) {
            Process caller = resolve(pending.from);
            debug("reply " + requestId + " flags " + flags + " pid " + result
                + (caller == null ? " without a caller" : ""));
            if (caller != null) {
                if ((flags & 1) != 0) {
                    caller.remoteLinkEstablished(result.toPID());
                }
                if ((flags & 2) != 0) {
                    caller.addMonitor(requestId, result);
                }
            }
        }
        if (pending.blocking != null) {
            pending.blocking.complete(result);
        } else {
            deliver(pending, requestId, result);
        }
    }

    /**
     * The connection to a node is gone: every spawn request that was still
     * waiting for its reply fails with `noconnection`. A new process may
     * or may not have been created; a linked one receives an exit signal
     * with the same reason from the link bookkeeping.
     */
    static void connectionLost(Atom node) {
        for (Reference requestId : new ArrayList<>(PENDING.keySet())) {
            Pending pending = PENDING.get(requestId);
            if (pending == null || !pending.node.equals(node)) {
                continue;
            }
            if (!PENDING.remove(requestId, pending)) {
                continue;
            }
            if (pending.blocking != null) {
                pending.blocking.complete(NOCONNECTION);
            } else {
                deliver(pending, requestId, NOCONNECTION);
            }
        }
    }

    /**
     * A SPAWN_REQUEST arrived from a peer. The new process is created but
     * not scheduled until the reply is on its way, so the reply always
     * precedes an exit signal the process may send when it terminates.
     */
    static void received(DistConnection connection, Atom peer, Reference requestId, PID from,
        Tuple mfa, List options, Term args) {
        if (!peer.equals(from.node())) {
            return;
        }
        Options parsed = new Options();
        if (!parsed.parse(options)) {
            reply(connection, from, requestId, 0, BADOPT);
            return;
        }
        Atom module;
        Atom function;
        int arity;
        try {
            module = mfa.element(1).toAtom();
            function = mfa.element(2).toAtom();
            arity = mfa.element(3).toInteger().toInt();
        } catch (RuntimeException e) {
            replyIf(connection, from, requestId, 0, BADARG, parsed.replyMode);
            return;
        }
        if (!(args instanceof List) || arity < 0 || args.toList().length() != arity) {
            replyIf(connection, from, requestId, 0, BADARG, parsed.replyMode);
            return;
        }
        Process child;
        try {
            child = prepareChild(module, function, args.toList());
        } catch (Throwable e) {
            replyIf(connection, from, requestId, 0, SYSTEM_LIMIT, parsed.replyMode);
            return;
        }
        int flags = 0;
        if (parsed.link && child.linkReceived(from)) {
            flags |= 1;
        }
        if (parsed.monitor && child.addMonitoredBy(requestId, from)) {
            flags |= 2;
        }
        replyIf(connection, from, requestId, flags, child.pid(), parsed.replyMode);
        VirtualMachine.instance().schedule(child);
    }

    private static void spawnLocal(Reference requestId, PID from, Atom module, Atom function,
        List args, Options options) {
        Process caller = resolve(from);
        Process child;
        try {
            child = prepareChild(module, function, args);
        } catch (Throwable e) {
            sendReply(from, options.replyTag, options.replyMode, requestId, SYSTEM_LIMIT);
            return;
        }
        int flags = 0;
        if (caller != null) {
            if (options.link && child.addLink(from)) {
                caller.addLink(child.pid());
                flags |= 1;
            }
            if (options.monitor && child.addMonitoredBy(requestId, from)) {
                caller.addMonitor(requestId, child.pid());
                flags |= 2;
            }
        }
        sendReply(from, options.replyTag, options.replyMode, requestId, child.pid());
        VirtualMachine.instance().schedule(child);
    }

    /**
     * A remote fun spawn is encoded as erlang:apply(Fun, []). The peer can
     * not look a lambda up in the export table, so it starts the process
     * directly from the decoded Fun instead.
     */
    private static Process prepareChild(Atom module, Atom function, List args) {
        if (ERLANG.equals(module) && APPLY.equals(function) && args.length() == 2
            && args.head() instanceof Fun && args.tail().head() instanceof List
            && args.tail().head().toList().length() == 0) {
            return VirtualMachine.instance().prepare((Fun) args.head());
        }
        return VirtualMachine.instance().prepare(module, function, args);
    }

    private static void reply(DistConnection connection, PID to, Reference requestId,
        int flags, Term result) {
        try {
            connection.sendSpawnReply(requestId, to, flags, result);
        } catch (java.io.IOException e) {
            // The caller learns about the closed connection when the
            // connection is torn down.
        }
    }

    private static void replyIf(DistConnection connection, PID to, Reference requestId,
        int flags, Term result, int replyMode) {
        boolean success = result instanceof PID;
        if (replyMode == REPLY_NO) {
            return;
        }
        if (success && replyMode == REPLY_ERROR_ONLY) {
            return;
        }
        if (!success && replyMode == REPLY_SUCCESS_ONLY) {
            return;
        }
        reply(connection, to, requestId, flags, result);
    }

    private static void fail(Reference requestId, PID from, Atom node,
        CompletableFuture<Term> blocking, Term reason, Term replyTag, int replyMode) {
        if (blocking != null) {
            blocking.complete(reason);
        } else {
            sendReply(from, replyTag, replyMode, requestId, reason);
        }
    }

    private static void deliver(Pending pending, Reference requestId, Term result) {
        sendReply(pending.from, pending.replyTag, pending.replyMode, requestId, result);
    }

    private static void sendReply(PID to, Term replyTag, int replyMode, Reference requestId,
        Term result) {
        boolean success = result instanceof PID;
        if (replyMode == REPLY_NO) {
            return;
        }
        if (success && replyMode == REPLY_ERROR_ONLY) {
            return;
        }
        if (!success && replyMode == REPLY_SUCCESS_ONLY) {
            return;
        }
        Process process = resolve(to);
        if (process != null) {
            process.send(Tuple.of(replyTag, requestId, success ? OK : ERROR, result));
        }
    }

    private static Process resolve(PID pid) {
        ProcessOrPort process = ProcessRegistry.resolve(pid);
        return process instanceof Process ? (Process) process : null;
    }

    private static void debug(String message) {
        if (Boolean.getBoolean("jerlang.dist.debug")) {
            System.err.println("jerlang dist: " + message);
        }
    }

    /**
     * The options of one spawn request, split into the ones the peer has to
     * know about and the ones that only control the reply message.
     */
    private static final class Options {

        private boolean link;
        private boolean monitor;
        private int replyMode = REPLY_YES;
        private Term replyTag = SPAWN_REPLY;

        boolean parse(List options) {
            List rest = options;
            while (rest.length() > 0) {
                Term option = rest.head();
                if (LINK.equals(option)) {
                    link = true;
                } else if (MONITOR.equals(option)) {
                    monitor = true;
                } else if (option instanceof Tuple) {
                    Tuple tuple = (Tuple) option;
                    if (tuple.arity() != 2) {
                        return false;
                    }
                    if (MONITOR.equals(tuple.element(1))) {
                        if (!tuple.element(2).isList() || tuple.element(2).toList().length() > 0) {
                            return false;
                        }
                        monitor = true;
                    } else if (REPLY_TAG.equals(tuple.element(1))) {
                        replyTag = tuple.element(2);
                    } else if (REPLY.equals(tuple.element(1))) {
                        Term value = tuple.element(2);
                        if (YES.equals(value)) {
                            replyMode = REPLY_YES;
                        } else if (NO.equals(value)) {
                            replyMode = REPLY_NO;
                        } else if (ERROR_ONLY.equals(value)) {
                            replyMode = REPLY_ERROR_ONLY;
                        } else if (SUCCESS_ONLY.equals(value)) {
                            replyMode = REPLY_SUCCESS_ONLY;
                        } else {
                            return false;
                        }
                    } else {
                        return false;
                    }
                } else {
                    return false;
                }
                rest = rest.tail();
            }
            return true;
        }

        List forwarded() {
            List options = List.nil;
            if (replyMode != REPLY_YES) {
                options = new List(Tuple.of(REPLY, modeAtom(replyMode)), options);
            }
            if (monitor) {
                options = new List(MONITOR, options);
            }
            if (link) {
                options = new List(LINK, options);
            }
            return options;
        }

        private static Term modeAtom(int mode) {
            switch (mode) {
            case REPLY_NO:
                return NO;
            case REPLY_ERROR_ONLY:
                return ERROR_ONLY;
            case REPLY_SUCCESS_ONLY:
                return SUCCESS_ONLY;
            default:
                return YES;
            }
        }

    }

    private static final class Pending {

        private final PID from;
        private final Atom node;
        private final Term replyTag;
        private final int replyMode;
        private final CompletableFuture<Term> blocking;

        Pending(PID from, Atom node, Term replyTag, int replyMode, CompletableFuture<Term> blocking) {
            this.from = from;
            this.node = node;
            this.replyTag = replyTag;
            this.replyMode = replyMode;
            this.blocking = blocking;
        }

    }

}
