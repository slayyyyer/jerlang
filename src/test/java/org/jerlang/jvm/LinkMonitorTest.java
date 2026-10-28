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
 * Runs link_demo.beam, compiled from src/test/erl/link_demo.erl by
 * Erlang/OTP 29, against local links, monitors and exit signals.
 */
public class LinkMonitorTest {

    private static final Path SOURCE = Paths.get("src/test/resources/link_demo.beam");
    private static final Path LOCAL = Paths.get("link_demo.beam");

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
    public void testLinkedProcessDiesWithItsCrashedPeer() throws Throwable {
        assertEquals(Atom.of("ok"), apply("linked_crash"));
    }

    @Test
    public void testTrapExitTurnsSignalsIntoMessages() throws Throwable {
        assertEquals(Atom.of("ok"), apply("trapped_crash"));
    }

    @Test
    public void testExitSignalTerminatesPeer() throws Throwable {
        assertEquals(Atom.of("ok"), apply("exit_signal"));
    }

    @Test
    public void testKillIsUncatchable() throws Throwable {
        assertEquals(Atom.of("ok"), apply("kill"));
    }

    @Test
    public void testUnlinkStopsTheExitSignal() throws Throwable {
        assertEquals(Atom.of("ok"), apply("unlink_test"));
    }

    @Test
    public void testDemonitorSuppressesDown() throws Throwable {
        assertEquals(Atom.of("ok"), apply("demonitor_test"));
    }

    @Test
    public void testMonitoringDeadProcessReportsNoproc() throws Throwable {
        assertEquals(Atom.of("ok"), apply("monitor_dead"));
    }

    @Test
    public void testLinkingDeadProcessReportsNoproc() throws Throwable {
        assertEquals(Atom.of("ok"), apply("link_dead"));
    }

    @Test
    public void testWhereisRegisteredAndIsProcessAlive() throws Throwable {
        assertEquals(Atom.of("ok"), apply("registry"));
    }

    private Term apply(String function) throws Throwable {
        return Erlang.apply(Atom.of("link_demo"), Atom.of(function), List.nil);
    }

}
