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
 * Runs gen_udp_demo.beam, compiled from src/test/erl/gen_udp_demo.erl by
 * Erlang/OTP 29, against the native gen_udp and inet modules.
 */
public class GenUdpTest {

    private static final Path SOURCE = Paths.get("src/test/resources/gen_udp_demo.beam");
    private static final Path LOCAL = Paths.get("gen_udp_demo.beam");

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
    public void testPassiveDatagramRoundtripFromCompiledErlang() throws Throwable {
        assertEquals(Atom.of("ok"), apply("roundtrip"));
    }

    @Test
    public void testActiveModeDeliversDatagramsFromCompiledErlang() throws Throwable {
        assertEquals(Atom.of("ok"), apply("active_roundtrip"));
    }

    @Test
    public void testConnectedSendFromCompiledErlang() throws Throwable {
        assertEquals(Atom.of("ok"), apply("connected_roundtrip"));
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
    public void testInetOptionsOnUdpSocketFromCompiledErlang() throws Throwable {
        assertEquals(Atom.of("ok"), apply("getopts_test"));
    }

    @Test
    public void testMulticastRoundtripFromCompiledErlang() throws Throwable {
        assertEquals(Atom.of("ok"), apply("multicast_roundtrip"));
    }

    @Test
    public void testMulticastMembershipSetoptsFromCompiledErlang() throws Throwable {
        assertEquals(Atom.of("ok"), apply("multicast_setopts"));
    }

    private Term apply(String function) throws Throwable {
        return Erlang.apply(Atom.of("gen_udp_demo"), Atom.of(function), List.nil);
    }

}
