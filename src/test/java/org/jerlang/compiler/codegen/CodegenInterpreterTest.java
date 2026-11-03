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
import org.jerlang.type.Map;
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
            "         len/1, greet/0, reverse/1, is_hello/1, classify/1, pick/1,",
            "         sign/1, alt/1, no_case/1, no_if/0, make_map/1, map_a/1,",
            "         map_other/0, map_value/1, safe/1, safe_of/1, uncaught/1, risky/1,",
            "         logic/2, negate/1, after_value/1, after_error/1, after_throw/1,",
            "         after_catch/1, after_catch_error/1, after_no_of/1,",
            "         receive_queued/0, receive_selective/0, receive_after/0, receive_timeout/1]).",
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
            "is_hello(_) -> false.",
            "classify(X) ->",
            "    case X of",
            "        0 -> zero;",
            "        N when N > 0 -> positive;",
            "        _ -> negative",
            "    end.",
            "pick(X) ->",
            "    case X of",
            "        1 -> A = one;",
            "        _ -> A = other",
            "    end,",
            "    A.",
            "sign(N) ->",
            "    if N > 0 -> positive;",
            "       N < 0 -> negative;",
            "       true -> zero",
            "    end.",
            "alt(X) when X =:= 1; X =:= 2 -> small;",
            "alt(_) -> other.",
            "no_case(X) -> case X of only -> ok end.",
            "no_if() -> if false -> ok end.",
            "make_map(X) -> #{a => X, b => 2}.",
            "map_a(M) -> M#{a => 3}.",
            "map_other() -> #{b => 2}.",
            "map_value(#{a := V}) -> V;",
            "map_value(_) -> none.",
            "safe(X) ->",
            "    try risky(X) catch",
            "        error:badarg -> {error, badarg};",
            "        throw:Reason -> {throw, Reason}",
            "    end.",
            "uncaught(X) -> try risky(X) catch error:badarg -> caught end.",
            "safe_of(X) ->",
            "    try risky(X) of",
            "        V -> {ok, V}",
            "    catch",
            "        error:badarg -> badarg;",
            "        throw:Reason -> {thrown, Reason}",
            "    end.",
            "risky(0) -> throw(zero);",
            "risky(1) -> error(badarg);",
            "risky(X) -> X.",
            "logic(A, B) -> {A andalso B, A orelse B}.",
            "negate(X) -> -X.",
            "after_value(X) ->",
            "    put(after_ran, false),",
            "    V = try risky(X) after put(after_ran, true) end,",
            "    {V, get(after_ran)}.",
            "after_error(X) ->",
            "    put(after_ran, false),",
            "    try try risky(X) after put(after_ran, true) end",
            "    catch error:badarg -> get(after_ran) end.",
            "after_throw(X) ->",
            "    put(after_ran, false),",
            "    try try risky(X) after put(after_ran, true) end",
            "    catch throw:zero -> get(after_ran) end.",
            "after_catch(X) ->",
            "    put(after_ran, false),",
            "    {try risky(X) catch error:badarg -> caught",
            "     after put(after_ran, true) end, get(after_ran)}.",
            "after_catch_error(X) ->",
            "    put(after_ran, false),",
            "    try",
            "        try risky(X) catch error:_ -> error(caught_error)",
            "        after put(after_ran, true) end",
            "    catch error:caught_error -> get(after_ran) end.",
            "after_no_of(X) ->",
            "    put(after_ran, false),",
            "    try",
            "        try risky(X) of only_zero -> ok",
            "        after put(after_ran, true) end",
            "    catch error:{try_clause, _} -> get(after_ran) end.",
            "receive_queued() ->",
            "    erlang:send(self(), {echo, 7}),",
            "    receive {echo, X} -> X end.",
            "receive_selective() ->",
            "    erlang:send(self(), {msg, other, 1}),",
            "    erlang:send(self(), {msg, wanted, 2}),",
            "    receive {msg, wanted, X} -> X end.",
            "receive_after() ->",
            "    erlang:send_after(0, self(), {echo, 9}),",
            "    receive {echo, X} -> X after 1000 -> timeout end.",
            "receive_timeout(T) ->",
            "    receive {echo, X} -> X after T -> timeout end."), StandardCharsets.UTF_8);

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
        assertEquals(Atom.of("zero"), apply(module, "classify", 1, Integer.ZERO));
        assertEquals(Atom.of("positive"), apply(module, "classify", 1, Integer.of(2)));
        assertEquals(Atom.of("negative"), apply(module, "classify", 1, Integer.of(-1)));
        assertEquals(Atom.of("one"), apply(module, "pick", 1, Integer.ONE));
        assertEquals(Atom.of("other"), apply(module, "pick", 1, Integer.of(2)));
        assertEquals(Atom.of("positive"), apply(module, "sign", 1, Integer.ONE));
        assertEquals(Atom.of("negative"), apply(module, "sign", 1, Integer.of(-1)));
        assertEquals(Atom.of("zero"), apply(module, "sign", 1, Integer.ZERO));
        assertEquals(Atom.of("small"), apply(module, "alt", 1, Integer.ONE));
        assertEquals(Atom.of("other"), apply(module, "alt", 1, Integer.of(3)));
        assertError(module, "no_case", 1,
            org.jerlang.type.Tuple.of(Atom.of("case_clause"), Atom.of("other")),
            Atom.of("other"));
        assertError(module, "no_if", 0, Atom.of("if_clause"));
        Term map = apply(module, "make_map", 1, Integer.ONE);
        assertEquals(Integer.ONE, ((Map) map).get(Atom.of("a")));
        assertEquals(Integer.of(2), ((Map) map).get(Atom.of("b")));
        Term updated = apply(module, "map_a", 1, map);
        assertEquals(Integer.of(3), ((Map) updated).get(Atom.of("a")));
        assertEquals(Integer.of(7), apply(module, "map_value", 1,
            apply(module, "make_map", 1, Integer.of(7))));
        assertEquals(Atom.of("none"), apply(module, "map_value", 1,
            apply(module, "map_other", 0)));
        assertEquals(org.jerlang.type.Tuple.of(Atom.of("throw"), Atom.of("zero")),
            apply(module, "safe", 1, Integer.ZERO));
        assertEquals(org.jerlang.type.Tuple.of(Atom.of("error"), Atom.of("badarg")),
            apply(module, "safe", 1, Integer.ONE));
        assertEquals(Integer.of(2), apply(module, "safe", 1, Integer.of(2)));
        assertEquals(org.jerlang.type.Tuple.of(Atom.of("ok"), Integer.of(2)),
            apply(module, "safe_of", 1, Integer.of(2)));
        assertEquals(org.jerlang.type.Tuple.of(Atom.of("thrown"), Atom.of("zero")),
            apply(module, "safe_of", 1, Integer.ZERO));
        assertEquals(Atom.of("badarg"), apply(module, "safe_of", 1, Integer.ONE));
        assertError(module, "uncaught", 1, Atom.of("zero"), Integer.ZERO);
        assertEquals(org.jerlang.type.Tuple.of(Atom.of("false"), Atom.of("true")),
            apply(module, "logic", 2, Atom.of("true"), Atom.of("false")));
        assertEquals(org.jerlang.type.Tuple.of(Atom.of("false"), Atom.of("true")),
            apply(module, "logic", 2, Atom.of("false"), Atom.of("true")));
        assertEquals(org.jerlang.type.Tuple.of(Atom.of("x"), Atom.of("true")),
            apply(module, "logic", 2, Atom.of("true"), Atom.of("x")));
        assertEquals(org.jerlang.type.Tuple.of(Atom.of("false"), Atom.of("x")),
            apply(module, "logic", 2, Atom.of("false"), Atom.of("x")));
        assertEquals(Integer.of(-3), apply(module, "negate", 1, Integer.of(3)));
        assertEquals(org.jerlang.type.Tuple.of(Integer.of(2), Atom.of("true")),
            apply(module, "after_value", 1, Integer.of(2)));
        assertEquals(Atom.of("true"), apply(module, "after_error", 1, Integer.ONE));
        assertEquals(Atom.of("true"), apply(module, "after_throw", 1, Integer.ZERO));
        assertEquals(org.jerlang.type.Tuple.of(Atom.of("caught"), Atom.of("true")),
            apply(module, "after_catch", 1, Integer.ONE));
        assertEquals(Atom.of("true"), apply(module, "after_catch_error", 1, Integer.ONE));
        assertEquals(Atom.of("true"), apply(module, "after_no_of", 1, Integer.of(2)));
        assertEquals(Integer.of(7), apply(module, "receive_queued", 0));
        assertEquals(Integer.of(2), apply(module, "receive_selective", 0));
        assertEquals(Integer.of(9), apply(module, "receive_after", 0));
        assertEquals(Atom.of("timeout"), apply(module, "receive_timeout", 1, Integer.ZERO));
        try {
            apply(module, "only_zero", 1, Integer.of(1));
            fail("expected function_clause");
        } catch (org.jerlang.erts.erlang.Error error) {
            assertEquals(Atom.of("function_clause"), error.reason());
        }
    }

    private static void assertError(Module module, String function, int arity,
        Term reason, Term... arguments) throws Throwable {
        try {
            apply(module, function, arity, arguments);
            fail("expected " + reason);
        } catch (org.jerlang.erts.erlang.Error error) {
            assertEquals(reason, error.reason());
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
