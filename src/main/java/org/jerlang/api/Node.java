package org.jerlang.api;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.lang.management.ManagementFactory;
import java.time.Duration;
import java.util.ArrayList;
import java.util.function.Function;

import javax.management.JMException;
import javax.management.ObjectName;

import org.jerlang.CodePaths;
import org.jerlang.Module;
import org.jerlang.ModuleRegistry;
import org.jerlang.jvm.HandleTable;
import org.jerlang.stdlib.Java;
import org.jerlang.type.Atom;
import org.jerlang.type.Fun;
import org.jerlang.type.List;
import org.jerlang.type.Str;
import org.jerlang.type.Term;
import org.jerlang.type.Tuple;
import org.jerlang.vm.Metrics;
import org.jerlang.vm.VirtualMachine;

/**
 * = Embedding a node
 *
 * A node owns the runtime for as long as it is open: it resets the process-wide
 * code path and module registry, applies its {@link NodeOptions}, starts the
 * schedulers, registers the java module when reflection was asked for, and on
 * {@link #close()} stops the schedulers, drains the object handle table and
 * clears that state again.
 *
 * ----
 * Node node = Node.start(NodeOptions.builder().reflection(true).build());
 * node.registerFunction("my_mod", "echo", 1, args -> args[0]);
 * Term reply = node.apply("gen_server", "call", pid, request, Integer.of(5000));
 * node.close();
 * ----
 *
 * Only one node may be open in a JVM. Close it before starting the next one;
 * the next start gets a clean code path and module registry, not the previous
 * node's state. A failed start releases everything it had already created.
 *
 * {@code apply} takes both a module name and an Erlang fun.
 * {@code registerFunction} makes a Java lambda callable from Erlang through
 * the normal module registry. {@link Bridge} stays as it is: a static
 * convenience over a default node.
 */
public final class Node implements AutoCloseable {

    private static Node active;

    private final NodeOptions options;
    private volatile boolean closed;
    private ObjectName metricsName;

    private Node(NodeOptions options) {
        this.options = options;
    }

    public static Node start() {
        return start(NodeOptions.defaults());
    }

    /**
     * Start a node. The process-wide code path and module registry are reset
     * first, then the options are applied. If anything fails, the VM is shut
     * down, the registries are reset again and no node is left active.
     */
    public static synchronized Node start(NodeOptions options) {
        if (options == null) {
            throw new IllegalArgumentException("options must not be null");
        }
        if (active != null && !active.closed) {
            throw new IllegalStateException("a node is already running");
        }
        CodePaths.reset();
        ModuleRegistry.reset();
        VirtualMachine virtualMachine = VirtualMachine.instance();
        try {
            applyStartupPaths(options);
            if (options.schedulers() > 0) {
                virtualMachine.start(options.schedulers());
            } else {
                virtualMachine.start();
            }
            virtualMachine.handleTable().setMaxHandles(options.maxHandles());
            Node node = new Node(options);
            if (options.reflection()) {
                node.registerJavaModule();
            }
            node.registerMetricsMBean();
            active = node;
            return node;
        } catch (RuntimeException | Error failure) {
            try {
                virtualMachine.shutdown();
            } catch (RuntimeException | Error cleanupFailure) {
                failure.addSuppressed(cleanupFailure);
            }
            ModuleRegistry.reset();
            CodePaths.reset();
            virtualMachine.handleTable().setMaxHandles(HandleTable.DEFAULT_MAX_HANDLES);
            active = null;
            throw failure;
        }
    }

    /**
     * Apply the node's code path and OTP root after the global path has been
     * reset. Resolving the OTP root happens here, before the schedulers start,
     * so a bad installation path is a clean startup failure.
     */
    private static void applyStartupPaths(NodeOptions options) {
        java.util.List<String> paths = new ArrayList<>();
        for (Path directory : options.codePaths()) {
            paths.add(CodePaths.normalizePath(directory.toString()));
        }
        if (options.otpRoot() != null) {
            paths.addAll(otpPaths(options.otpRoot()));
        }
        CodePaths.addPathsa(paths);
    }

    /** The kernel and stdlib ebins of an OTP installation, in that order. */
    private static java.util.List<String> otpPaths(Path root) {
        Path lib = root.resolve("lib");
        if (!Files.isDirectory(lib)) {
            throw new IllegalArgumentException("OTP root has no lib directory: " + root);
        }
        java.util.List<String> paths = new ArrayList<>();
        for (String application : java.util.List.of("kernel", "stdlib")) {
            Path ebin = newestEbin(lib, application);
            if (ebin == null) {
                throw new IllegalArgumentException(
                    "OTP " + application + " not found under " + lib);
            }
            paths.add(CodePaths.normalizePath(ebin.toString()));
        }
        return paths;
    }

    private static Path newestEbin(Path lib, String application) {
        Path newest = null;
        try (DirectoryStream<Path> applications =
            Files.newDirectoryStream(lib, application + "-*")) {
            for (Path directory : applications) {
                Path ebin = directory.resolve("ebin");
                if (!Files.isDirectory(ebin)) {
                    continue;
                }
                if (newest == null || directory.getFileName().toString()
                    .compareTo(newest.getFileName().toString()) > 0) {
                    newest = directory;
                }
            }
        } catch (IOException e) {
            throw new IllegalArgumentException("can not read OTP lib directory: " + lib, e);
        }
        return newest == null ? null : newest.resolve("ebin");
    }

    /**
     * Register the java module on this node. Without it there is no
     * java:new/2, so Erlang code cannot reach an arbitrary JVM class.
     */
    private void registerJavaModule() {
        Module module = new Module(Java.class, Atom.of("java"));
        module.export();
        ModuleRegistry.register(module);
    }

    public NodeOptions options() {
        return options;
    }

    public boolean hasReflection() {
        return options.reflection();
    }

    public boolean isClosed() {
        return closed;
    }

    /**
     * The name of the node, or `nonode@nohost` while it has none.
     */
    public Atom name() {
        return VirtualMachine.instance().node();
    }

    /**
     * A reading of the node's runtime counters: processes, ports, run queue
     * depth, reductions, mailbox messages, scheduler utilisation and uptime.
     * The fields and the text format are documented in docs/operations.md.
     */
    public Metrics metrics() {
        ensureOpen();
        return Metrics.snapshot();
    }

    /**
     * Register this node's counters under a stable JMX object name. The name
     * includes the node's own name, so a client can query a named node without
     * knowing its PID or startup order.
     */
    private void registerMetricsMBean() {
        try {
            ObjectName name = new ObjectName("org.jerlang:type=Node,name="
                + ObjectName.quote(name().toString()));
            ManagementFactory.getPlatformMBeanServer()
                .registerMBean(new NodeMetrics(this), name);
            metricsName = name;
        } catch (JMException failure) {
            throw new IllegalStateException("can not register node metrics", failure);
        }
    }

    private void unregisterMetricsMBean() {
        if (metricsName == null) {
            return;
        }
        try {
            ManagementFactory.getPlatformMBeanServer().unregisterMBean(metricsName);
        } catch (JMException ignored) {
            // A registry that already removed the bean must not turn close
            // into a failure; the node is going away either way.
        } finally {
            metricsName = null;
        }
    }

    /**
     * Call an exported Erlang function.
     */
    public Term apply(String module, String function, Term... arguments) {
        ensureOpen();
        return Bridge.call(module, function, arguments);
    }

    /**
     * Call an exported Erlang function with a deadline. A call that does not
     * finish in time throws {@link Module.ApplyTimeoutException} after the
     * process has been killed.
     */
    public Term apply(String module, String function, Duration timeout,
        Term... arguments) {
        ensureOpen();
        return Bridge.call(module, function, timeout, arguments);
    }

    /**
     * Apply an Erlang fun. The call goes through erlang:apply/2, so the fun
     * runs in a process of its own instead of on the caller's thread.
     */
    public Term apply(Fun fun, Term... arguments) {
        ensureOpen();
        return Bridge.call("erlang", "apply", fun, List.of(arguments));
    }

    /**
     * Make a Java lambda callable from Erlang as module:function/arity. A
     * RuntimeException from the lambda reaches the caller as an Erlang error
     * whose reason is {@code {java_exception, ClassName, Message}}.
     */
    public Node registerFunction(String moduleName, String function, int arity,
        Function<Term[], Term> body) {
        ensureOpen();
        if (body == null) {
            throw new IllegalArgumentException("body must not be null");
        }
        Atom name = Atom.of(moduleName);
        Module module = ModuleRegistry.loaded(name);
        if (module == null) {
            module = Module.empty(name);
            ModuleRegistry.register(module);
        }
        module.export(function, arity, arguments -> {
            try {
                return body.apply(arguments);
            } catch (org.jerlang.erts.erlang.Error error) {
                throw error;
            } catch (RuntimeException failure) {
                org.jerlang.erts.erlang.Error error = new org.jerlang.erts.erlang.Error(
                    Tuple.of(Atom.of("java_exception"),
                        Str.of(failure.getClass().getName()),
                        Str.of(failure.getMessage() == null ? "" : failure.getMessage())));
                error.initCause(failure);
                throw error;
            }
        });
        return this;
    }

    /**
     * Stop the schedulers, drain the object handle table and clear the code
     * path and module registry. Applications and processes are stopped before
     * the modules they use are removed. Closing twice does nothing the second
     * time.
     */
    @Override
    public synchronized void close() {
        // Serialize with start: another thread must not start a new node while
        // this node is still shutting down and resetting process-wide state.
        synchronized (Node.class) {
            if (closed) {
                return;
            }
            closed = true;
            active = null;
            VirtualMachine virtualMachine = VirtualMachine.instance();
            try {
                unregisterMetricsMBean();
                virtualMachine.shutdown();
            } finally {
                ModuleRegistry.reset();
                CodePaths.reset();
                virtualMachine.handleTable().setMaxHandles(HandleTable.DEFAULT_MAX_HANDLES);
            }
        }
    }

    private void ensureOpen() {
        if (closed) {
            throw new IllegalStateException("node is closed");
        }
    }

}
