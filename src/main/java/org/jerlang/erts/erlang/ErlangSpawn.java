package org.jerlang.erts.erlang;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.jerlang.Process;
import org.jerlang.ProcessPriority;
import org.jerlang.ProcessSignals;
import org.jerlang.erts.dist.Distribution;
import org.jerlang.erts.dist.SpawnRequests;
import org.jerlang.type.Atom;
import org.jerlang.type.Fun;
import org.jerlang.type.List;
import org.jerlang.type.PID;
import org.jerlang.type.Reference;
import org.jerlang.type.Term;
import org.jerlang.type.Tuple;
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

    public static Term spawn_link_1(Fun fun) {
        Process child = VirtualMachine.instance().prepare(fun);
        return start(child, SpawnOptions.LINK);
    }

    public static Term spawn_link_3(Atom module, Atom fun, List args) {
        Process child = VirtualMachine.instance().prepare(module, fun, args);
        return start(child, SpawnOptions.LINK);
    }

    public static Term spawn_link_4(Atom node, Atom module, Atom fun, List args) {
        return spawn_opt_5(node, module, fun, args, List.of(Atom.of("link")));
    }

    public static Term spawn_monitor_1(Fun fun) {
        Process child = VirtualMachine.instance().prepare(fun);
        return start(child, SpawnOptions.MONITOR);
    }

    public static Term spawn_monitor_3(Atom module, Atom fun, List args) {
        Process child = VirtualMachine.instance().prepare(module, fun, args);
        return start(child, SpawnOptions.MONITOR);
    }

    public static Term spawn_opt_2(Fun fun, List options) {
        SpawnOptions parsed = SpawnOptions.parse(options);
        return start(VirtualMachine.instance().prepare(fun), parsed);
    }

    public static Term spawn_opt_4(Atom module, Atom fun, List args, List options) {
        SpawnOptions parsed = SpawnOptions.parse(options);
        return start(VirtualMachine.instance().prepare(module, fun, args), parsed);
    }

    /**
     * Spawn with options, possibly on another node. Only `link` and
     * `monitor` have meaning across nodes; the process options that only
     * affect the local scheduler are applied when the child is local.
     */
    public static Term spawn_opt_5(Atom node, Atom module, Atom fun, List args, List options) {
        SpawnOptions parsed = SpawnOptions.parse(options);
        VirtualMachine vm = VirtualMachine.instance();
        if (node.equals(vm.node())) {
            return start(vm.prepare(module, fun, args), parsed);
        }
        if (!Distribution.connect(node)) {
            throw new Error(Atom.of("noconnection"));
        }
        CompletableFuture<Term> result = new CompletableFuture<>();
        Reference requestId = SpawnRequests.request(ProcessSignals.self().pid(), node, module, fun,
            args, parsed.forwarded(), result);
        Term reply;
        try {
            reply = result.get(REMOTE_SPAWN_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new Error(Atom.of("noconnection"));
        } catch (ExecutionException | TimeoutException e) {
            throw new Error(Atom.of("noconnection"));
        }
        if (!(reply instanceof PID)) {
            throw new Error(reply);
        }
        return parsed.monitor ? Tuple.of(reply, requestId) : reply;
    }

    private static Term start(Process child, SpawnOptions options) {
        if (options.priority != null) {
            child.setPriority(options.priority);
        }
        Process caller = ProcessSignals.self();
        Reference reference = null;
        if (options.link) {
            caller.link(child.pid());
        }
        if (options.monitor) {
            reference = caller.monitor(child.pid());
        }
        VirtualMachine.instance().schedule(child);
        return reference == null ? child.pid() : Tuple.of(child.pid(), reference);
    }

    private static Term start(Process child, int mode) {
        return start(child, new SpawnOptions(mode));
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

    /**
     * The subset of spawn_opt/2's options that changes how the process is
     * started. Unknown options are rejected, as in Erlang/OTP.
     */
    static final class SpawnOptions {

        static final int LINK = 1;
        static final int MONITOR = 2;

        final boolean link;
        final boolean monitor;
        final ProcessPriority priority;

        SpawnOptions(int mode) {
            this.link = (mode & LINK) != 0;
            this.monitor = (mode & MONITOR) != 0;
            this.priority = null;
        }

        SpawnOptions(boolean link, boolean monitor, ProcessPriority priority) {
            this.link = link;
            this.monitor = monitor;
            this.priority = priority;
        }

        static SpawnOptions parse(List options) {
            boolean link = false;
            boolean monitor = false;
            ProcessPriority priority = null;
            List rest = options;
            while (rest.length() > 0) {
                Term option = rest.head();
                if (Atom.of("link").equals(option)) {
                    link = true;
                } else if (Atom.of("monitor").equals(option)) {
                    monitor = true;
                } else if (option instanceof Tuple) {
                    Tuple tuple = (Tuple) option;
                    if (tuple.arity() != 2) {
                        throw Error.badarg;
                    }
                    String name = tuple.element(1).toString();
                    switch (name) {
                    case "priority":
                        priority = priority(tuple.element(2));
                        break;
                    case "fullsweep_after":
                    case "min_heap_size":
                    case "min_bin_vheap_size":
                    case "max_heap_size":
                    case "message_queue_data":
                    case "scheduler":
                    case "sensitive":
                        // Accepted for source compatibility. The JVM does
                        // not expose these scheduler and heap knobs.
                        break;
                    default:
                        throw Error.badarg;
                    }
                } else {
                    throw Error.badarg;
                }
                rest = rest.tail();
            }
            return new SpawnOptions(link, monitor, priority);
        }

        private static ProcessPriority priority(Term term) {
            if (term instanceof Atom) {
                String name = term.toString();
                for (ProcessPriority priority : ProcessPriority.values()) {
                    if (priority.toAtom().toString().equals(name)) {
                        return priority;
                    }
                }
            }
            throw Error.badarg;
        }

        List forwarded() {
            List options = List.nil;
            if (monitor) {
                options = new List(Atom.of("monitor"), options);
            }
            if (link) {
                options = new List(Atom.of("link"), options);
            }
            return options;
        }

    }

}
