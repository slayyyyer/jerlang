package org.jerlang.compiler.codegen;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
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
            "         receive_queued/0, receive_selective/0, receive_after/0, receive_timeout/1,",
            "         fun_add/1, fun_sub/1, fun_multi/0, fun_zero/0, fun_nested/1,",
            "         fun_apply/2, fun_make/1, bin_lit/0, bin_empty/0, bin_construct/1,",
            "         bin_match/1, bin_tail/1, bin_tail_used/1, bin_string/1, bin_size/1,",
            "         double_evens/1, pairs/0, map_comp/1, bin_comp/1,",
            "         fun_ref/1, fun_local/1, named_fact/1, match_expr/1,",
            "         bad_match/1]).",
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
            "    receive {echo, X} -> X after T -> timeout end.",
            "fun_add(Y) ->",
            "    F = fun(X) -> X + Y end,",
            "    F(5).",
            "fun_sub(Y) ->",
            "    F = fun(X) -> X - Y end,",
            "    F(10).",
            "fun_multi() ->",
            "    F = fun(0) -> zero; (N) -> N end,",
            "    {F(0), F(2)}.",
            "fun_zero() ->",
            "    F = fun() -> 7 end,",
            "    F().",
            "fun_nested(Y) ->",
            "    F = fun(X) -> (fun(Z) -> X + Z end)(Y) end,",
            "    F(5).",
            "fun_apply(F, X) -> F(X).",
            "fun_make(Y) -> fun(X) -> X + Y end.",
            "bin_lit() -> <<1, 2, 3>>.",
            "bin_empty() -> <<>>.",
            "bin_construct(X) -> <<X, 2, \"ab\">>.",
            "bin_match(Bin) ->",
            "    case Bin of",
            "        <<A, B, Rest/binary>> -> {A, B, Rest};",
            "        _ -> no",
            "    end.",
            "bin_tail(<<_A:8, Rest/binary>>) -> Rest.",
            "bin_tail_used(<<A:8, Rest/binary>>) -> {A, Rest}.",
            "bin_string(<<\"hi\", Rest/binary>>) -> Rest.",
            "bin_size(Bin) -> byte_size(Bin).",
            "double_evens(L) -> [X * 2 || X <- L, X > 1].",
            "pairs() -> [{X, Y} || X <- [1, 2], Y <- [a, b]].",
            "map_comp(L) -> #{X => X + 1 || X <- L}.",
            "bin_comp(L) -> << <<X>> || X <- L >>.",
            "fun_ref(L) -> F = fun lists:reverse/1, F(L).",
            "fun_local(X) -> F = fun codegen_sample:fact/1, F(X).",
            "named_fact(N) -> F = fun G(0) -> 1; G(M) -> M * G(M - 1) end, F(N).",
            "match_expr(X) -> {A, B} = {X, 2}, [A, B].",
            "bad_match(X) -> try {A} = {X, 2} catch error:{badmatch, _} -> badmatch end."),
            StandardCharsets.UTF_8);

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
        assertEquals(Integer.of(8), apply(module, "fun_add", 1, Integer.of(3)));
        assertEquals(Integer.of(6), apply(module, "fun_sub", 1, Integer.of(4)));
        assertEquals(org.jerlang.type.Tuple.of(Atom.of("zero"), Integer.of(2)),
            apply(module, "fun_multi", 0));
        assertEquals(Integer.of(7), apply(module, "fun_zero", 0));
        assertEquals(Integer.of(15), apply(module, "fun_nested", 1, Integer.of(10)));
        Term fun = apply(module, "fun_make", 1, Integer.of(3));
        assertEquals(Integer.of(8), apply(module, "fun_apply", 2, fun, Integer.of(5)));
        assertEquals(org.jerlang.type.List.of(Integer.of(3), Integer.of(2), Integer.of(1)),
            apply(module, "fun_ref", 1,
                org.jerlang.type.List.of(Integer.ONE, Integer.of(2), Integer.of(3))));
        assertEquals(Integer.of(120), apply(module, "fun_local", 1, Integer.of(5)));
        assertEquals(Integer.of(120), apply(module, "named_fact", 1, Integer.of(5)));
        assertEquals(Integer.of("2432902008176640000"),
            apply(module, "fact", 1, Integer.of(20)));
        assertEquals(Integer.of(3628800), apply(module, "named_fact", 1, Integer.of(10)));
        assertEquals(org.jerlang.type.List.of(Integer.of(7), Integer.of(2)),
            apply(module, "match_expr", 1, Integer.of(7)));
        assertEquals(Atom.of("badmatch"), apply(module, "bad_match", 1, Integer.of(7)));
        assertEquals(org.jerlang.type.Binary.of(1, 2, 3), apply(module, "bin_lit", 0));
        assertEquals(org.jerlang.type.Binary.of(), apply(module, "bin_empty", 0));
        assertEquals(org.jerlang.type.Binary.of(7, 2, 97, 98),
            apply(module, "bin_construct", 1, Integer.of(7)));
        assertEquals(org.jerlang.type.Tuple.of(Integer.of(1), Integer.of(2),
            org.jerlang.type.Binary.of(3)),
            apply(module, "bin_match", 1, org.jerlang.type.Binary.of(1, 2, 3)));
        assertEquals(Atom.of("no"), apply(module, "bin_match", 1, Atom.of("no")));
        assertEquals(org.jerlang.type.Binary.of(2, 3),
            apply(module, "bin_tail", 1, org.jerlang.type.Binary.of(1, 2, 3)));
        assertEquals(org.jerlang.type.Tuple.of(Integer.of(1),
            org.jerlang.type.Binary.of(2, 3)),
            apply(module, "bin_tail_used", 1, org.jerlang.type.Binary.of(1, 2, 3)));
        assertEquals(org.jerlang.type.Binary.of(4, 5),
            apply(module, "bin_string", 1, org.jerlang.type.Binary.of(104, 105, 4, 5)));
        assertEquals(Integer.of(3), apply(module, "bin_size", 1,
            org.jerlang.type.Binary.of(1, 2, 3)));
        assertEquals(org.jerlang.type.List.of(Integer.of(4), Integer.of(6)),
            apply(module, "double_evens", 1,
                org.jerlang.type.List.of(Integer.ONE, Integer.of(2), Integer.of(3))));
        assertEquals(org.jerlang.type.List.of(
            org.jerlang.type.Tuple.of(Integer.ONE, Atom.of("a")),
            org.jerlang.type.Tuple.of(Integer.ONE, Atom.of("b")),
            org.jerlang.type.Tuple.of(Integer.of(2), Atom.of("a")),
            org.jerlang.type.Tuple.of(Integer.of(2), Atom.of("b"))),
            apply(module, "pairs", 0));
        assertEquals(org.jerlang.type.Binary.of(1, 2, 3),
            apply(module, "bin_comp", 1,
                org.jerlang.type.List.of(Integer.ONE, Integer.of(2), Integer.of(3))));
        Map comprehensionMap = (Map) apply(module, "map_comp", 1,
            org.jerlang.type.List.of(Integer.ONE, Integer.of(2)));
        assertEquals(Integer.of(2), comprehensionMap.get(Integer.ONE));
        assertEquals(Integer.of(3), comprehensionMap.get(Integer.of(2)));
        try {
            apply(module, "only_zero", 1, Integer.of(1));
            fail("expected function_clause");
        } catch (org.jerlang.erts.erlang.Error error) {
            assertEquals(Atom.of("function_clause"), error.reason());
        }

        assertEquals(Atom.of("codegen_sample"),
            apply(module, "module_info", 1, Atom.of("module")));
        assertEquals(Atom.of("false"), apply(module, "module_info", 1, Atom.of("native")));
        assertEquals(org.jerlang.type.List.nil,
            apply(module, "module_info", 1, Atom.of("native_addresses")));
        Term digest = apply(module, "module_info", 1, Atom.of("md5"));
        assertTrue(digest instanceof org.jerlang.type.Binary);
        assertEquals(16, ((org.jerlang.type.Binary) digest).toByteArray().length);

        org.jerlang.type.List exports = apply(module, "module_info", 1,
            Atom.of("exports")).toList();
        assertTrue(contains(exports, org.jerlang.type.Tuple.of(Atom.of("add"), Integer.of(2))));
        assertTrue(contains(exports, org.jerlang.type.Tuple.of(Atom.of("module_info"),
            Integer.ZERO)));
        org.jerlang.type.List functions = apply(module, "module_info", 1,
            Atom.of("functions")).toList();
        assertTrue(contains(functions, org.jerlang.type.Tuple.of(Atom.of("risky"), Integer.ONE)));
        assertEquals(3, apply(module, "module_info", 1, Atom.of("compile"))
            .toList().length());
        assertTrue(apply(module, "module_info", 1, Atom.of("attributes"))
            instanceof org.jerlang.type.List);

        try {
            apply(module, "risky", 1, Integer.ONE);
            fail("expected badarg");
        } catch (org.jerlang.erts.erlang.Error error) {
            org.jerlang.type.List stack = error.stacktrace().toList();
            assertTrue("empty stacktrace", stack.length() > 0);
            org.jerlang.type.Tuple frame = stack.head().toTuple();
            assertEquals(Atom.of("risky"), frame.element(2));
            org.jerlang.type.List location = frame.element(4).toList();
            assertEquals(Atom.of("line"), location.tail().head().toTuple().element(1));
            assertTrue(location.tail().head().toTuple().element(2).toInteger().toInt() > 0);
        }
    }

    private static boolean contains(org.jerlang.type.List list, Term term) {
        while (list != org.jerlang.type.List.nil) {
            if (list.head().equals(term)) {
                return true;
            }
            list = list.tail();
        }
        return false;
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
