package org.jerlang.compiler.codegen;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.fail;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;

import org.jerlang.FunctionSignature;
import org.jerlang.Module;
import org.jerlang.ModuleLoader;
import org.jerlang.ModuleRegistry;
import org.jerlang.compiler.parse.Parser;
import org.jerlang.compiler.preprocess.Preprocessor;
import org.jerlang.type.Atom;
import org.jerlang.type.Integer;
import org.jerlang.type.Str;
import org.jerlang.type.Term;
import org.jerlang.vm.VirtualMachine;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/**
 * The interpreter is the second consumer of codegen output. It must not be the
 * judge, but a module that only OTP can load is not finished either.
 */
public class CodegenInterpreterTest {

    private Path directory;

    @Before
    public void startVirtualMachine() {
        VirtualMachine.instance().start();
    }

    @After
    public void stop() {
        ModuleRegistry.unregister(Atom.of("codegen_sample"));
        VirtualMachine.instance().shutdown();
        try {
            if (directory != null) {
                Files.deleteIfExists(directory.resolve("codegen_sample.beam"));
                Files.deleteIfExists(directory.resolve("codegen_sample.erl"));
                Files.deleteIfExists(directory);
            }
        } catch (Exception ignored) {
            // The temporary files are only diagnostics for a failed load.
        }
    }

    @Test
    public void interpreterLoadsAndRunsGeneratedModule() throws Throwable {
        directory = Files.createTempDirectory("codegen-interpreter");
        Path source = directory.resolve("codegen_sample.erl");
        Files.write(source, Arrays.asList(
            "-module(codegen_sample).",
            "-export([add/2, answer/0, fact/1, choose/1, pair/1, only_zero/1,",
            "         len/1, greet/0, reverse/1, is_hello/1]).",
            "add(A, B) -> A + B.",
            "answer() -> 42.",
            "fact(0) -> 1;",
            "fact(N) -> N * fact(N - 1).",
            "choose(X) when X > 0 -> positive;",
            "choose(_) -> other.",
            "pair(X) -> {X, answer}.",
            "only_zero(0) -> ok.",
            "len([]) -> 0;",
            "len([_|T]) -> 1 + len(T).",
            "greet() -> \"hello\".",
            "reverse(L) -> lists:reverse(L).",
            "is_hello(\"hello\") -> true;",
            "is_hello(_) -> false."), StandardCharsets.UTF_8);

        List<Term> forms = Parser.parseForms(Preprocessor.scanFile(source));
        Path beam = directory.resolve("codegen_sample.beam");
        Files.write(beam, Codegen.compile(forms));

        Module module = ModuleLoader.load(Atom.of("codegen_sample"), beam.toFile());
        assertNotNull("JErlang could not load the generated module", module);
        ModuleRegistry.register(module);

        assertEquals(Integer.of(5), apply(module, "add", 2,
            Integer.of(2), Integer.of(3)));
        assertEquals(Integer.of(42), apply(module, "answer", 0));
        assertEquals(Integer.of(120), apply(module, "fact", 1, Integer.of(5)));
        assertEquals(Atom.of("positive"), apply(module, "choose", 1, Integer.of(1)));
        assertEquals(Atom.of("other"), apply(module, "choose", 1, Integer.of(0)));
        assertEquals(org.jerlang.type.Tuple.of(Integer.of(7), Atom.of("answer")),
            apply(module, "pair", 1, Integer.of(7)));
        assertEquals(Integer.of(3), apply(module, "len", 1,
            org.jerlang.type.List.of(Atom.of("a"), Atom.of("b"), Atom.of("c"))));
        assertEquals(Integer.ZERO, apply(module, "len", 1, org.jerlang.type.List.nil));
        assertEquals(Str.of("hello"), apply(module, "greet", 0));
        assertEquals(org.jerlang.type.List.of(Integer.of(3), Integer.of(2), Integer.of(1)),
            apply(module, "reverse", 1,
                org.jerlang.type.List.of(Integer.ONE, Integer.of(2), Integer.of(3))));
        assertEquals(Atom.of("true"), apply(module, "is_hello", 1, Str.of("hello")));
        assertEquals(Atom.of("false"), apply(module, "is_hello", 1, Str.of("bye")));
        try {
            apply(module, "only_zero", 1, Integer.of(1));
            fail("expected function_clause");
        } catch (org.jerlang.erts.erlang.Error error) {
            assertEquals(Atom.of("function_clause"), error.reason());
        }
    }

    private static Term apply(Module module, String function, int arity, Term... arguments) {
        org.jerlang.type.List params = org.jerlang.type.List.nil;
        for (int index = arguments.length - 1; index >= 0; index--) {
            params = new org.jerlang.type.List(arguments[index], params);
        }
        return module.apply(new FunctionSignature(module.name(), Atom.of(function),
            Integer.of(arity)), params);
    }

}
