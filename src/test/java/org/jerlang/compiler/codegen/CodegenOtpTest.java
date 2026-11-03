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
            "         logic/2, negate/1]).",
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
            "negate(X) -> -X."), StandardCharsets.UTF_8);

        List<Term> forms = Parser.parseForms(Preprocessor.scanFile(source));
        byte[] beam = Codegen.compile(forms);
        Path beamFile = directory.resolve("codegen_sample.beam");
        Files.write(beamFile, beam);

        String expression = "erlang:display(["
            + "codegen_sample:add(2, 3), "
            + "codegen_sample:answer(), "
            + "codegen_sample:fact(5), "
            + "codegen_sample:choose(1), "
            + "codegen_sample:choose(0), "
            + "codegen_sample:pair(7), "
            + "codegen_sample:len([a, b, c]), "
            + "codegen_sample:len([]), "
            + "codegen_sample:greet(), "
            + "codegen_sample:reverse([1, 2, 3]), "
            + "codegen_sample:is_hello([104, 101, 108, 108, 111]), "
            + "codegen_sample:is_hello([98, 121, 101]), "
            + "codegen_sample:classify(0), "
            + "codegen_sample:classify(2), "
            + "codegen_sample:classify(-1), "
            + "codegen_sample:pick(1), "
            + "codegen_sample:pick(2), "
            + "codegen_sample:sign(1), "
            + "codegen_sample:sign(-1), "
            + "codegen_sample:sign(0), "
            + "codegen_sample:alt(1), "
            + "codegen_sample:alt(3), "
            + "try codegen_sample:no_case(other) catch error:{case_clause, other} -> case_clause end, "
            + "try codegen_sample:no_if() catch error:if_clause -> if_clause end, "
            + "maps:get(a, codegen_sample:make_map(1)), "
            + "maps:get(b, codegen_sample:make_map(1)), "
            + "maps:get(a, codegen_sample:map_a(codegen_sample:make_map(1))), "
            + "codegen_sample:map_value(codegen_sample:make_map(7)), "
            + "codegen_sample:map_value(codegen_sample:map_other()), "
            + "codegen_sample:safe(0), "
            + "codegen_sample:safe(1), "
            + "codegen_sample:safe(2), "
            + "codegen_sample:safe_of(2), "
            + "codegen_sample:safe_of(0), "
            + "codegen_sample:safe_of(1), "
            + "try codegen_sample:uncaught(0) catch throw:zero -> zero end, "
            + "codegen_sample:logic(true, false), "
            + "codegen_sample:logic(false, true), "
            + "codegen_sample:logic(true, x), "
            + "codegen_sample:logic(false, x), "
            + "codegen_sample:negate(3), "
            + "try codegen_sample:only_zero(1) catch error:function_clause -> function_clause end]), halt().";
        ProcessBuilder builder = new ProcessBuilder(erl.toString(), "-noshell",
            "-pa", directory.toAbsolutePath().toString(), "-eval", expression);
        builder.redirectErrorStream(true);
        Process process = builder.start();
        String output = new String(process.getInputStream().readAllBytes(),
            StandardCharsets.UTF_8).replace("\r\n", "\n");
        assertTrue("OTP did not finish: " + output, process.waitFor(60, TimeUnit.SECONDS));
        assertEquals(output, 0, process.exitValue());
        assertEquals("[5,42,120,positive,other,{7,answer},3,0,\"hello\",[3,2,1],true,false,"
            + "zero,positive,negative,one,other,positive,negative,zero,small,other,"
            + "case_clause,if_clause,1,2,3,7,none,{throw,zero},{error,badarg},2,"
            + "{ok,2},{thrown,zero},badarg,zero,{false,true},{false,true},{x,true},"
            + "{false,x},-3,function_clause]\n", output);
        assertTrue("the generated file was not recognized as a module",
            Files.size(beamFile) > 100);
    }

}
