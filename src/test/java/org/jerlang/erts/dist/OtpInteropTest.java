package org.jerlang.erts.dist;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;

import org.jerlang.Process;
import org.jerlang.ProcessRegistry;
import org.jerlang.erts.Erlang;
import org.jerlang.type.Atom;
import org.jerlang.type.Integer;
import org.jerlang.type.List;
import org.jerlang.type.PID;
import org.jerlang.type.Term;
import org.jerlang.type.Tuple;
import org.jerlang.vm.VirtualMachine;
import org.junit.After;
import org.junit.Assume;
import org.junit.Before;
import org.junit.Test;

/**
 * Opt-in test against a real Erlang/OTP node.
 *
 * Start the peer with:
 *
 *   erl -name peer@localhost -setcookie dist-test-cookie -noshell -eval \
 *     'register(echo, spawn(fun Loop() -> receive {From, Msg} ->
 *        From ! {echo, Msg}, Loop() end end)),
 *      io:format("READY ~p~n", [node()]),
 *      receive after infinity -> ok end.'
 *
 * Then run:
 *
 *   mvn test -Dtest=OtpInteropTest -Djerlang.dist.otp=true
 *
 * Without -Djerlang.dist.otp=true the test is skipped.
 */
public class OtpInteropTest {

    private static final Atom LOCAL = Atom.of("jerlang@localhost");
    private static final Atom PEER = Atom.of("peer@localhost");
    private static final Path DIST_DEMO = Paths.get("dist_demo.beam");

    private VirtualMachine vm;
    private Atom previousNode;
    private int previousCreation;
    private boolean started;

    @Before
    public void setUp() throws Exception {
        Assume.assumeTrue("set -Djerlang.dist.otp=true to run this test",
            Boolean.getBoolean("jerlang.dist.otp"));

        vm = VirtualMachine.instance();
        previousNode = vm.node();
        previousCreation = vm.nodeCreation();
        vm.setNode(LOCAL, 1);
        DistCookie.setCookie("dist-test-cookie");
        Files.copy(Paths.get("src/test/resources/dist_demo.beam"), DIST_DEMO,
            StandardCopyOption.REPLACE_EXISTING);
        vm.start();
        started = true;
    }

    @After
    public void tearDown() {
        if (!started) {
            return;
        }
        Distribution.disconnectAll();
        vm.shutdown();
        DistCookie.reset();
        vm.setNode(previousNode, previousCreation);
        try {
            Files.deleteIfExists(DIST_DEMO);
        } catch (Exception ignored) {
            // Nothing to clean up if the file was never copied.
        }
    }

    @Test
    public void pingAndExchangeMessagesWithOtp() throws Throwable {
        assertEquals(Atom.of("pong"),
            Erlang.apply(Atom.of("net_adm"), Atom.of("ping"), List.of(PEER)));
        assertTrue(Distribution.isConnected(PEER));
        assertEquals(List.of(PEER), Erlang.nodes());

        Process sink = new Process(new PID(123));
        ProcessRegistry.register(sink);
        ProcessRegistry.register(Atom.of("sink"), sink.id());

        Tuple target = Tuple.of(Atom.of("echo"), PEER);
        Term hello = Tuple.of(Atom.of("hello"), Integer.of(42));
        Distribution.send(target, sink.pid(), Tuple.of(sink.pid(), hello));

        long deadline = System.currentTimeMillis() + 5000;
        while (!sink.hasMessage() && System.currentTimeMillis() < deadline) {
            Thread.sleep(10);
        }
        assertTrue("OTP did not send a reply", sink.hasMessage());
        Term reply = sink.nextMessage();
        assertNotNull(reply);
        assertEquals(Tuple.of(Atom.of("echo"), hello), reply);
    }

    @Test
    public void compiledErlangCallsAnotherNode() throws Throwable {
        assertEquals(Atom.of("pong"),
            Erlang.apply(Atom.of("net_adm"), Atom.of("ping"), List.of(PEER)));

        Term message = Tuple.of(Atom.of("hello"), Integer.of(42));
        Term reply = Erlang.apply(Atom.of("dist_demo"), Atom.of("echo_roundtrip"),
            List.of(PEER, message));
        assertEquals(message, reply);

        assertEquals(LOCAL,
            Erlang.apply(Atom.of("dist_demo"), Atom.of("node_name"), List.nil));
        assertEquals(List.of(PEER),
            Erlang.apply(Atom.of("dist_demo"), Atom.of("peer_nodes"), List.nil));
    }

}
