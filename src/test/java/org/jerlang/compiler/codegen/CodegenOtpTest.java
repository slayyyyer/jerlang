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
            + "try codegen_sample:only_zero(1) catch error:function_clause -> function_clause end]), halt().";
        ProcessBuilder builder = new ProcessBuilder(erl.toString(), "-noshell",
            "-pa", directory.toAbsolutePath().toString(), "-eval", expression);
        builder.redirectErrorStream(true);
        Process process = builder.start();
        String output = new String(process.getInputStream().readAllBytes(),
            StandardCharsets.UTF_8).replace("\r\n", "\n");
        assertTrue("OTP did not finish: " + output, process.waitFor(60, TimeUnit.SECONDS));
        assertEquals(output, 0, process.exitValue());
        assertEquals("[5,42,120,positive,other,{7,answer},3,0,\"hello\",[3,2,1],true,false,function_clause]\n",
            output);
        assertTrue("the generated file was not recognized as a module",
            Files.size(beamFile) > 100);
    }

}
