package org.jerlang.jvm;

import static org.junit.Assert.assertEquals;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;

import org.jerlang.Module;
import org.jerlang.ModuleRegistry;
import org.jerlang.erts.Erlang;
import org.jerlang.type.Atom;
import org.jerlang.type.Boolean;
import org.jerlang.type.Integer;
import org.jerlang.type.List;
import org.jerlang.type.Term;
import org.jerlang.vm.VirtualMachine;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/**
 * A lists.beam in the working directory is loaded instead of the built-in
 * lists module. Functions the BEAM file declares but does not implement, which
 * is how OTP ships BIFs and NIFs, still come from the built-in module.
 *
 * The fixture is a stand-in module named lists that implements only sort/1 and
 * usort/1, so the test can tell which module answered.
 */
public class ListsModuleTest {

    private static final Path SOURCE = Paths.get("src/test/resources/override/lists.beam");
    private static final Path LOCAL = Paths.get("lists.beam");
    private static final Atom LISTS = Atom.of("lists");

    private Module builtin;

    @Before
    public void start() throws Exception {
        builtin = ModuleRegistry.get(LISTS);
        Files.copy(SOURCE, LOCAL, StandardCopyOption.REPLACE_EXISTING);
        VirtualMachine.instance().start();
        // Force the BEAM file to replace the built-in module.
        ModuleRegistry.get(LISTS);
    }

    @After
    public void stop() throws Exception {
        VirtualMachine.instance().shutdown();
        Files.deleteIfExists(LOCAL);
        ModuleRegistry.register(builtin);
    }

    @Test
    public void testBeamModuleWins() throws Throwable {
        assertEquals(Atom.of("from_beam"),
            apply("sort", List.of(Integer.of(3), Integer.of(1))));
        assertEquals(Atom.of("from_beam"), apply("usort", List.nil));
    }

    @Test
    public void testBuiltinFunctionsStillWork() throws Throwable {
        assertEquals(List.of(Integer.of(2), Integer.of(1)),
            apply("reverse", List.of(Integer.of(1), Integer.of(2))));
        assertEquals(List.of(Integer.of(2), Integer.of(1), Integer.of(9)),
            apply("reverse", List.of(Integer.of(1), Integer.of(2)),
                List.of(Integer.of(9))));
        assertEquals(Boolean.am_true,
            apply("member", Integer.of(1), List.of(Integer.of(1))));
        assertEquals(Boolean.am_false,
            apply("member", Integer.of(2), List.of(Integer.of(1))));
    }

    private Term apply(String function, Term... arguments) throws Throwable {
        return Erlang.apply(LISTS, Atom.of(function), List.of(arguments));
    }

}
