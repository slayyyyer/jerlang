package org.jerlang.compiler.codegen;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;

import org.jerlang.compiler.TestOtp;
import org.jerlang.compiler.parse.Parser;
import org.jerlang.compiler.preprocess.Preprocessor;
import org.jerlang.type.Term;
import org.junit.Assume;
import org.junit.Test;

/**
 * = Codegen's OTP oracle
 *
 * The generated file is loaded by a real OTP 29 node and the exported functions
 * are called. That checks the chunks, operand encoding, labels, Y registers and
 * the BIF import table in one pass; the interpreter is not the judge.
 */
public class CodegenOtpTest {

    @Test
    public void generatedModuleLoadsAndRuns() throws Throwable {
        Path erl = TestOtp.erl();
        Assume.assumeTrue("no Erlang installation found", erl != null);

        Path directory = Files.createTempDirectory("codegen-oracle");
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
        byte[] beam = Codegen.compile(forms);
        Path beamFile = directory.resolve("codegen_sample.beam");
        Files.write(beamFile, beam);

        String[] groups = {
            "codegen_sample:add(2, 3), codegen_sample:answer(), codegen_sample:fact(5), "
                + "codegen_sample:choose(1), codegen_sample:choose(0), codegen_sample:pair(7)",
            "codegen_sample:len([a, b, c]), codegen_sample:len([]), codegen_sample:greet(), "
                + "codegen_sample:reverse([1, 2, 3]), "
                + "codegen_sample:is_hello([104, 101, 108, 108, 111]), "
                + "codegen_sample:is_hello([98, 121, 101])",
            "codegen_sample:classify(0), codegen_sample:classify(2), "
                + "codegen_sample:classify(-1), codegen_sample:pick(1), codegen_sample:pick(2), "
                + "codegen_sample:sign(1), codegen_sample:sign(-1), codegen_sample:sign(0), "
                + "codegen_sample:alt(1), codegen_sample:alt(3)",
            "try codegen_sample:no_case(other) catch error:{case_clause, other} -> case_clause end",
            "try codegen_sample:no_if() catch error:if_clause -> if_clause end",
            "maps:get(a, codegen_sample:make_map(1)), "
                + "maps:get(b, codegen_sample:make_map(1)), "
                + "maps:get(a, codegen_sample:map_a(codegen_sample:make_map(1))), "
                + "codegen_sample:map_value(codegen_sample:make_map(7)), "
                + "codegen_sample:map_value(codegen_sample:map_other())",
            "codegen_sample:safe(0), codegen_sample:safe(1), codegen_sample:safe(2), "
                + "codegen_sample:safe_of(2), codegen_sample:safe_of(0), codegen_sample:safe_of(1)",
            "try codegen_sample:uncaught(0) catch throw:zero -> zero end",
            "codegen_sample:logic(true, false), codegen_sample:logic(false, true), "
                + "codegen_sample:logic(true, x), codegen_sample:logic(false, x), "
                + "codegen_sample:negate(3)",
            "codegen_sample:after_value(2), codegen_sample:after_error(1), "
                + "codegen_sample:after_throw(0), codegen_sample:after_catch(1), "
                + "codegen_sample:after_catch_error(1), codegen_sample:after_no_of(2)",
            "codegen_sample:receive_queued(), codegen_sample:receive_selective(), "
                + "codegen_sample:receive_after(), codegen_sample:receive_timeout(0)",
            "codegen_sample:fun_add(3), codegen_sample:fun_sub(4), "
                + "codegen_sample:fun_multi(), codegen_sample:fun_zero(), "
                + "codegen_sample:fun_nested(10), "
                + "codegen_sample:fun_apply(fun(A) -> A * 2 end, 6)",
            "codegen_sample:bin_lit(), codegen_sample:bin_empty(), "
                + "codegen_sample:bin_construct(7), "
                + "codegen_sample:bin_match(<<1,2,3>>), codegen_sample:bin_match(no), "
                + "codegen_sample:bin_tail(<<1,2,3>>), "
                + "codegen_sample:bin_tail_used(<<1,2,3>>), "
                + "codegen_sample:bin_string(<<104,105,4,5>>), "
                + "codegen_sample:bin_size(<<1,2,3>>)",
            "codegen_sample:double_evens([1,2,3]), codegen_sample:pairs(), "
                + "maps:get(1, codegen_sample:map_comp([1,2])), "
                + "maps:get(2, codegen_sample:map_comp([1,2])), "
                + "codegen_sample:bin_comp([1,2,3])",
            "try codegen_sample:only_zero(1) catch error:function_clause -> function_clause end",
            "codegen_sample:module_info(module), codegen_sample:module_info(native), "
                + "codegen_sample:module_info(native_addresses), "
                + "is_binary(codegen_sample:module_info(md5))",
            "is_list(codegen_sample:module_info(exports)), "
                + "is_list(codegen_sample:module_info(functions)), "
                + "is_list(codegen_sample:module_info(attributes))",
            "is_list(codegen_sample:module_info(compile)), "
                + "try codegen_sample:risky(1) catch error:badarg:Stack -> "
                + "{codegen_sample, risky, 1, Loc} = hd(Stack), "
                + "is_integer(proplists:get_value(line, Loc)) end",
            "codegen_sample:fun_ref([1,2,3]), codegen_sample:fun_local(5), "
                + "codegen_sample:named_fact(5)",
            "codegen_sample:fact(20), codegen_sample:named_fact(10)",
            "codegen_sample:match_expr(7), codegen_sample:bad_match(7)"
        };
        StringBuilder output = new StringBuilder();
        for (String group : groups) {
            String expression = "erlang:display([" + group + "]), halt().";
            ProcessBuilder builder = new ProcessBuilder(erl.toString(), "-noshell",
                "-pa", directory.toAbsolutePath().toString(), "-eval", expression);
            builder.redirectErrorStream(true);
            Process process = builder.start();
            String part = new String(process.getInputStream().readAllBytes(),
                StandardCharsets.UTF_8).replace("\r\n", "\n");
            assertTrue("OTP did not finish: " + part,
                process.waitFor(60, TimeUnit.SECONDS));
            assertEquals(part, 0, process.exitValue());
            output.append(part);
        }
        assertEquals("[5,42,120,positive,other,{7,answer}]\n"
            + "[3,0,\"hello\",[3,2,1],true,false]\n"
            + "[zero,positive,negative,one,other,positive,negative,zero,small,other]\n"
            + "[case_clause]\n"
            + "[if_clause]\n"
            + "[1,2,3,7,none]\n"
            + "[{throw,zero},{error,badarg},2,{ok,2},{thrown,zero},badarg]\n"
            + "[zero]\n"
            + "[{false,true},{false,true},{x,true},{false,x},-3]\n"
            + "[{2,true},true,true,{caught,true},true,true]\n"
            + "[7,2,9,timeout]\n"
            + "[8,6,{zero,2},7,15,12]\n"
            + "[<<1,2,3>>,<<>>,<<7,2,97,98>>,{1,2,<<3>>},no,<<2,3>>,{1,<<2,3>>},<<4,5>>,3]\n"
            + "[[4,6],[{1,a},{1,b},{2,a},{2,b}],2,3,<<1,2,3>>]\n"
            + "[function_clause]\n"
            + "[codegen_sample,false,[],true]\n"
            + "[true,true,true]\n"
            + "[true,true]\n"
            + "[[3,2,1],120,120]\n"
            + "[2432902008176640000,3628800]\n"
            + "[[7,2],badmatch]\n", output.toString());
        assertTrue("the generated file was not recognized as a module",
            Files.size(beamFile) > 100);
    }

}
