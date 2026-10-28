package org.jerlang.erts.erlang;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.jerlang.ProcessSignals;
import org.jerlang.erts.dist.Distribution;
import org.jerlang.erts.dist.SpawnRequests;
import org.jerlang.type.Atom;
import org.jerlang.type.Fun;
import org.jerlang.type.List;
import org.jerlang.type.PID;
import org.jerlang.type.Term;
import org.jerlang.vm.VirtualMachine;

public class ErlangSpawn {

    private static final long REMOTE_SPAWN_TIMEOUT_SECONDS = 30;

    private ErlangSpawn() {
    }

    public static Term dispatch(List params) {
        switch (params.length()) {
        case 1:
            return spawn_1(params.head().toFun());
        case 3:
            Atom m = params.head().toAtom();
            params = params.tail();
            Atom f = params.head().toAtom();
            params = params.tail();
            List a = params.head().toList();
            return spawn_3(m, f, a);
        case 4:
            Atom node = params.head().toAtom();
            params = params.tail();
            m = params.head().toAtom();
            params = params.tail();
            f = params.head().toAtom();
            params = params.tail();
            a = params.head().toList();
            return spawn_4(node, m, f, a);
        default:
            throw Error.badarg;
        }
    }

    /**
     * Returns the pid of a new process started by the application of
     * Fun to the empty list []. Otherwise works like spawn/3.
     *
     * http://www.erlang.org/doc/man/erlang.html#spawn-1
     */
    public static PID spawn_1(Fun fun) {
        return VirtualMachine.instance().spawn(fun).pid();
    }

    public static PID spawn_3(Atom module, Atom fun, List args) {
        return VirtualMachine.instance().spawn(module, fun, args).pid();
    }

    /**
     * Start a process on another node, and wait for that node to confirm
     * that the process was created. A node that can not be reached yields
     * a PID that names no process, as documented for spawn/4. When the
     * connection is lost while the request is in flight, or the peer does
     * not support remote spawn, the call raises an error instead.
     *
     * http://www.erlang.org/doc/man/erlang.html#spawn-4
     */
    public static PID spawn_4(Atom node, Atom module, Atom fun, List args) {
        VirtualMachine vm = VirtualMachine.instance();
        if (node.equals(vm.node())) {
            return spawn_3(module, fun, args);
        }
        if (!Distribution.connect(node)) {
            return vm.uselessPid();
        }
        CompletableFuture<Term> result = new CompletableFuture<>();
        SpawnRequests.request(ProcessSignals.self().pid(), node, module, fun, args, List.nil,
            result);
        Term reply;
        try {
            reply = result.get(REMOTE_SPAWN_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new Error(Atom.of("noconnection"));
        } catch (ExecutionException | TimeoutException e) {
            throw new Error(Atom.of("noconnection"));
        }
        if (reply instanceof PID) {
            return (PID) reply;
        }
        throw new Error(reply);
    }

}
