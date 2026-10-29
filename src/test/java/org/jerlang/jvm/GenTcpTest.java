package org.jerlang.jvm;

import static org.junit.Assert.assertEquals;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;

import org.jerlang.erts.Erlang;
import org.jerlang.type.Atom;
import org.jerlang.type.List;
import org.jerlang.type.Term;
import org.jerlang.vm.VirtualMachine;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/**
 * Runs gen_tcp_demo.beam, compiled from src/test/erl/gen_tcp_demo.erl by
 * Erlang/OTP 29, against the native gen_tcp and inet modules.
 */
public class GenTcpTest {

    private static final Path SOURCE = Paths.get("src/test/resources/gen_tcp_demo.beam");
    private static final Path LOCAL = Paths.get("gen_tcp_demo.beam");

    @Before
    public void start() throws Exception {
        Files.copy(SOURCE, LOCAL, StandardCopyOption.REPLACE_EXISTING);
        VirtualMachine.instance().start();
    }

    @After
    public void stop() throws Exception {
        VirtualMachine.instance().shutdown();
        Files.deleteIfExists(LOCAL);
    }

    @Test
    public void testPassiveRoundtripFromCompiledErlang() throws Throwable {
        assertEquals(Atom.of("ok"), apply("roundtrip"));
    }

    @Test
    public void testActiveModeDeliversMessagesFromCompiledErlang() throws Throwable {
        assertEquals(Atom.of("ok"), apply("active_roundtrip"));
    }

    @Test
    public void testListModeFromCompiledErlang() throws Throwable {
        assertEquals(Atom.of("ok"), apply("list_roundtrip"));
    }

    @Test
    public void testRecvTimeoutFromCompiledErlang() throws Throwable {
        assertEquals(Atom.of("ok"), apply("timeout_test"));
    }

    @Test
    public void testSetoptsAndGetoptsFromCompiledErlang() throws Throwable {
        assertEquals(Atom.of("ok"), apply("getopts_test"));
    }

    @Test
    public void testAddressHelpersFromCompiledErlang() throws Throwable {
        assertEquals(Atom.of("ok"), apply("address_helpers"));
    }

    @Test
    public void testListenDefaultsFromCompiledErlang() throws Throwable {
        assertEquals(Atom.of("ok"), apply("listen_default"));
    }

    @Test
    public void testCommonSocketOptionsFromCompiledErlang() throws Throwable {
        assertEquals(Atom.of("ok"), apply("socket_options"));
    }

    @Test
    public void testHalfCloseWithExitOnCloseDisabledFromCompiledErlang() throws Throwable {
        assertEquals(Atom.of("ok"), apply("half_close"));
    }

    @Test
    public void testSendTimeoutAndSendTimeoutCloseFromCompiledErlang() throws Throwable {
        assertEquals(Atom.of("ok"), apply("send_timeout"));
    }

    private Term apply(String function) throws Throwable {
        return Erlang.apply(Atom.of("gen_tcp_demo"), Atom.of(function), List.nil);
    }

}
