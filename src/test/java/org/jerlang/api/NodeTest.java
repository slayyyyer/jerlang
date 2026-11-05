package org.jerlang.api;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;

import org.jerlang.CodePaths;
import org.jerlang.Module;
import org.jerlang.ModuleRegistry;
import org.jerlang.erts.erlang.Error;
import org.jerlang.type.Atom;
import org.jerlang.type.Integer;
import org.jerlang.type.List;
import org.jerlang.type.Str;
import org.jerlang.type.Term;
import org.jerlang.type.Tuple;
import org.jerlang.vm.VirtualMachine;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/**
 * The embedding contract from docs/java-interop.md: a node owns the runtime,
 * reflection is off unless asked for, and closing it undoes both.
 */
public class NodeTest {

    @Before
    public void forgetJavaModule() {
        ModuleRegistry.unregister(Atom.of("java"));
    }

    @After
    public void resetRuntimeState() {
        ModuleRegistry.reset();
        CodePaths.reset();
        VirtualMachine.instance().handleTable().setMaxHandles(
            org.jerlang.jvm.HandleTable.DEFAULT_MAX_HANDLES);
    }

    @Test
    public void aPlainNodeHasNoJavaModule() {
        try (Node node = Node.start()) {
            assertFalse(node.hasReflection());
            assertNull(ModuleRegistry.get(Atom.of("java")));
        }
    }

    @Test
    public void reflectionRegistersTheJavaModule() {
        try (Node node = Node.start(NodeOptions.builder().reflection(true).build())) {
            assertTrue(node.hasReflection());
            Term builder = node.apply("java", "new", Str.of("java.lang.StringBuilder"), List.nil);
            node.apply("java", "call", builder, Str.of("append"), List.of(Str.of("hi")));
            assertEquals(Str.of("hi"),
                node.apply("java", "call", builder, Str.of("toString"), List.nil));
        }
    }

    @Test
    public void aRegisteredFunctionIsCallableFromErlang() {
        try (Node node = Node.start()) {
            node.registerFunction("my_mod", "echo", 1, args -> args[0]);
            assertEquals(Integer.of(7), node.apply("my_mod", "echo", Integer.of(7)));
        }
    }

    @Test
    public void anErlangFunCanBeApplied() {
        try (Node node = Node.start()) {
            Term fun = node.apply("erlang", "make_fun",
                Atom.of("lists"), Atom.of("reverse"), Integer.of(1));
            assertEquals(List.of(Integer.of(2), Integer.of(1)),
                node.apply(fun.toFun(), Integer.of(1), Integer.of(2)));
        }
    }

    @Test
    public void maxHandlesBoundsTheTable() {
        try (Node node = Node.start(NodeOptions.builder().reflection(true).maxHandles(1).build())) {
            node.apply("java", "new", Str.of("java.lang.StringBuilder"), List.nil);
            try {
                node.apply("java", "new", Str.of("java.lang.StringBuilder"), List.nil);
                fail("a second handle should exceed the cap");
            } catch (Error e) {
                assertEquals(Atom.of("system_limit"), e.reason());
            }
        }
    }

    @Test
    public void aNodeCanBeStartedAndClosedRepeatedly() throws Exception {
        Path first = Files.createTempDirectory("jerlang-node-first");
        Path second = Files.createTempDirectory("jerlang-node-second");

        for (int iteration = 0; iteration < 2; iteration++) {
            final int value = iteration;
            Path ebin = iteration == 0 ? first : second;
            Path previous = iteration == 0 ? second : first;
            try (Node node = Node.start(NodeOptions.builder()
                .schedulers(1).codePath(ebin).build())) {
                assertTrue(CodePaths.get().contains(CodePaths.normalizePath(ebin.toString())));
                assertFalse(CodePaths.get().contains(CodePaths.normalizePath(previous.toString())));
                node.registerFunction("only_here", "value", 0,
                    args -> Integer.of(value));
                assertEquals(Integer.of(value),
                    node.apply("only_here", "value"));
            }
            assertNull("a closed node must not leave its modules behind",
                ModuleRegistry.loaded(Atom.of("only_here")));
            assertEquals(0, VirtualMachine.instance().handleTable().size());
        }
    }

    @Test
    public void aSecondNodeCanNotStartWhileOneIsOpen() {
        try (Node node = Node.start()) {
            IllegalStateException failure = assertThrows(IllegalStateException.class,
                Node::start);
            assertEquals("a node is already running", failure.getMessage());
        }
    }

    @Test
    public void aFailedStartLeavesNoNodeBehind() throws Exception {
        Path root = Files.createTempDirectory("jerlang-bad-otp");
        Files.createDirectories(root.resolve("lib"));

        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
            () -> Node.start(NodeOptions.builder().otpRoot(root).build()));
        assertTrue(failure.getMessage(), failure.getMessage().contains("OTP kernel"));

        try (Node node = Node.start()) {
            node.registerFunction("after_failure", "value", 0, args -> Integer.of(1));
            assertEquals(Integer.of(1), node.apply("after_failure", "value"));
        }
    }

    @Test
    public void aCallbackThatThrowsReachesTheCaller() {
        try (Node node = Node.start()) {
            node.registerFunction("boom", "explode", 0, args -> {
                throw new IllegalStateException("callback failed");
            });

            Error direct = assertThrows(Error.class,
                () -> node.apply("boom", "explode"));
            assertEquals(Tuple.of(Atom.of("java_exception"),
                Str.of(IllegalStateException.class.getName()),
                Str.of("callback failed")), direct.reason());
            assertEquals(IllegalStateException.class, direct.getCause().getClass());

            Error throughErlang = assertThrows(Error.class,
                () -> node.apply("erlang", "apply",
                    Atom.of("boom"), Atom.of("explode"), List.nil));
            assertEquals(direct.reason(), throughErlang.reason());
        }
    }

    @Test
    public void aSlowCallbackTimesOutAndIsKilled() {
        try (Node node = Node.start()) {
            node.registerFunction("slow", "nap", 0, args -> {
                try {
                    Thread.sleep(500);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                return Atom.of("done");
            });

            Module.ApplyTimeoutException timeout = assertThrows(
                Module.ApplyTimeoutException.class,
                () -> node.apply("slow", "nap", Duration.ofMillis(20)));
            assertEquals(20L, timeout.timeoutMillis());
        }
    }

    @Test
    public void aNodeServesSeveralRequestsUntilClose() {
        Node node = Node.start();
        AtomicInteger next = new AtomicInteger();
        node.registerFunction("service", "next", 0,
            args -> Integer.of(next.incrementAndGet()));

        assertEquals(Integer.of(1), node.apply("service", "next"));
        assertEquals(Integer.of(2), node.apply("service", "next"));
        assertEquals(Integer.of(3), node.apply("service", "next"));

        node.close();
        assertTrue(node.isClosed());
        assertEquals(0, VirtualMachine.instance().handleTable().size());
        assertNull(ModuleRegistry.loaded(Atom.of("service")));
        node.close();
    }

    @Test
    public void closingReleasesHandlesAndTheJavaModule() {
        Node node = Node.start(NodeOptions.builder().reflection(true).build());
        node.apply("java", "new", Str.of("java.lang.StringBuilder"), List.nil);
        assertEquals(1, VirtualMachine.instance().handleTable().size());

        node.close();

        assertTrue(node.isClosed());
        assertEquals(0, VirtualMachine.instance().handleTable().size());
        assertNull(ModuleRegistry.get(Atom.of("java")));
        node.close();
        try {
            node.apply("lists", "reverse", List.nil);
            fail("a closed node must not run Erlang code");
        } catch (IllegalStateException e) {
            assertEquals("node is closed", e.getMessage());
        }
    }

}
