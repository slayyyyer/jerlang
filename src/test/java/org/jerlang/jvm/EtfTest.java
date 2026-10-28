package org.jerlang.jvm;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;

import org.jerlang.erts.Erlang;
import org.jerlang.erts.etf.ExternalTermReader;
import org.jerlang.erts.etf.ExternalTermWriter;
import org.jerlang.type.Atom;
import org.jerlang.type.Binary;
import org.jerlang.type.Integer;
import org.jerlang.type.List;
import org.jerlang.type.Map;
import org.jerlang.type.PID;
import org.jerlang.type.Term;
import org.jerlang.type.Tuple;
import org.jerlang.vm.VirtualMachine;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/**
 * Runs etf_demo.beam, which was compiled from src/test/erl/etf_demo.erl by
 * Erlang/OTP 29 and calls erlang:term_to_binary/1, erlang:binary_to_term/1
 * and erlang:node/1.
 */
public class EtfTest {

    private static final Path SOURCE = Paths.get("src/test/resources/etf_demo.beam");
    private static final Path LOCAL = Paths.get("etf_demo.beam");

    private static final Atom MODULE = Atom.of("etf_demo");
    private static final Atom NODE = Atom.of("nonode@nohost");

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
    public void testCompiledModuleRoundtripsATerm() throws Throwable {
        Map map = new Map();
        map.set(Atom.of("a"), Integer.of(1));
        Term term = Tuple.of(Atom.of("hello"),
            List.of(Integer.of(1), Integer.of(2), Atom.of("three")),
            map, Binary.of(1, 2, 3));

        Term result = Erlang.apply(MODULE, Atom.of("roundtrip"), List.of(term));
        assertEquals(term, result);
    }

    @Test
    public void testCompiledModuleEncodesTheSameBytesAsOtp() throws Throwable {
        Term term = Tuple.of(Atom.of("a"), Integer.of(1));
        assertEquals(Integer.of(8),
            Erlang.apply(MODULE, Atom.of("encoded_size"), List.of(term)));
    }

    @Test
    public void testNodeName() throws Throwable {
        assertEquals(NODE, Erlang.apply(MODULE, Atom.of("node_name"), List.nil));
        assertEquals(NODE, Erlang.apply(MODULE, Atom.of("pid_node"), List.nil));
    }

    @Test
    public void testTermToBinaryOfSelfReturnsADecodablePid() throws Throwable {
        Term encoded = Erlang.apply(MODULE, Atom.of("self_binary"), List.nil);
        assertTrue("expected a binary, got " + encoded, encoded instanceof Binary);

        Term decoded = new ExternalTermReader(((Binary) encoded).toByteArray()).read();
        assertTrue("expected a pid, got " + decoded, decoded instanceof PID);
        assertEquals(NODE, ((PID) decoded).node());
        assertEquals(0, ((PID) decoded).creation());
        assertTrue(decoded.toString() + " should be a local pid",
            decoded.toString().startsWith("<0."));
    }

    @Test
    public void testCompiledModuleDecodesARemotePid() throws Throwable {
        PID remote = new PID(Atom.of("peer@host"), 7, 2, 1);
        Term encoded = ExternalTermWriter.encode(remote);

        assertEquals(Atom.of("peer@host"),
            Erlang.apply(MODULE, Atom.of("binary_node"), List.of(encoded)));
    }

}
