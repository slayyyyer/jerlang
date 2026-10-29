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
import org.jerlang.type.Fun;
import org.jerlang.type.Integer;
import org.jerlang.type.List;
import org.jerlang.type.Term;
import org.jerlang.type.Tuple;
import org.jerlang.vm.VirtualMachine;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/**
 * Runs funs_demo.beam, which was compiled from src/test/erl/funs_demo.erl by
 * Erlang/OTP 29 and uses make_fun3, call_fun and call_fun2.
 */
public class FunsTest {

    private static final Path SOURCE = Paths.get("src/test/resources/funs_demo.beam");
    private static final Path LOCAL = Paths.get("funs_demo.beam");

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
    public void testFunWithFreeVariableAndHigherOrderCall() throws Throwable {
        // run/0 returns {adder(2)(10), twice(fun(X) -> X * 2 end, 5)}.
        Term result = Erlang.apply(Atom.of("funs_demo"), Atom.of("run"), List.nil);
        assertEquals(Tuple.of(Integer.of(12), Integer.of(20)), result);
    }

    @Test
    public void testMakeFun3CreatesAFun() throws Throwable {
        Term result = Erlang.apply(Atom.of("funs_demo"), Atom.of("adder"),
            List.of(Integer.of(5)));
        assertTrue("expected a fun, got " + result, result instanceof Fun);
        assertEquals(Atom.of("funs_demo"), result.toFun().signature().module());
        assertEquals(Atom.of("-adder/1-fun-0-"), result.toFun().signature().function());
        assertEquals(2, result.toFun().signature().fun_arity().toInt());
    }

    @Test
    public void testExternalTermFormatRoundTripsAFunWithFreeVariables() throws Throwable {
        Fun fun = Erlang.apply(Atom.of("funs_demo"), Atom.of("adder"),
            List.of(Integer.of(5))).toFun();

        Binary encoded = ExternalTermWriter.encode(fun);
        Fun decoded = new ExternalTermReader(encoded.toByteArray()).read().toFun();

        assertEquals(fun.signature(), decoded.signature());
        assertEquals(1, decoded.savedRegisters().length);
        assertEquals(Integer.of(5), decoded.savedRegisters()[0]);
        // twice(F, 1) = F(F(1)) = 11 when F adds 5.
        assertEquals(Integer.of(11), Erlang.apply(Atom.of("funs_demo"),
            Atom.of("twice"), List.of(decoded, Integer.of(1))));
    }

}
