package org.jerlang.compiler.codegen;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;

import org.jerlang.FunctionSignature;
import org.jerlang.Module;
import org.jerlang.ModuleLoader;
import org.jerlang.ModuleRegistry;
import org.jerlang.compiler.TestOtp;
import org.jerlang.compiler.parse.Parser;
import org.jerlang.compiler.preprocess.Preprocessor;
import org.jerlang.type.Atom;
import org.jerlang.type.Integer;
import org.jerlang.type.Term;
import org.jerlang.vm.VirtualMachine;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/**
 * = A5 corpus recompile
 *
 * The checked-in corpus is compiled from source by JErlang and then run through
 * both independent consumers. OTP is the oracle; JErlang is the second reader
 * and must agree on the return value.
 */
public class CodegenCorpusTest {

    private static final Path SOURCES = Paths.get("src/test/erl/corpus");

    private Path directory;

    @Before
    public void startVirtualMachine() {
        VirtualMachine.instance().start();
    }

    @After
    public void stop() {
        VirtualMachine.instance().shutdown();
    }

    @Test
    public void recompiledCorpusRunsInOtpAndJerlang() throws Throwable {
        List<Path> sources = sources();
        assertTrue("the codegen corpus must not be empty", sources.size() > 0);
        directory = Files.createTempDirectory("codegen-corpus");

        for (Path source : sources) {
            String filename = source.getFileName().toString();
            String moduleName = filename.substring(0, filename.length() - ".erl".length());
            List<Term> forms = Parser.parseForms(Preprocessor.scanFile(source));
            Path beam = directory.resolve(moduleName + ".beam");
            Files.write(beam, Codegen.compile(forms));

            verifyJerlang(moduleName, beam);
            verifyOtp(moduleName, beam);
        }
    }

    private void verifyJerlang(String moduleName, Path beam) throws Throwable {
        ModuleRegistry.unregister(Atom.of(moduleName));
        Module module = ModuleLoader.load(Atom.of(moduleName), beam.toFile());
        assertNotNull("JErlang could not load " + moduleName, module);
        ModuleRegistry.register(module);
        try {
            assertEquals("JErlang " + moduleName + " did not return ok",
                Atom.of("ok"),
                module.apply(new FunctionSignature(module.name(), Atom.of("run"),
                    Integer.ZERO), org.jerlang.type.List.nil));
        } finally {
            ModuleRegistry.unregister(Atom.of(moduleName));
        }
    }

    private void verifyOtp(String moduleName, Path beam) throws Exception {
        Path erl = TestOtp.erl();
        if (erl == null) {
            return;
        }
        String expression = "erlang:display(" + moduleName + ":run()), halt().";
        ProcessBuilder builder = new ProcessBuilder(erl.toString(), "-noshell",
            "-pa", directory.toAbsolutePath().toString(), "-eval", expression);
        builder.redirectErrorStream(true);
        Process process = builder.start();
        String output = new String(process.getInputStream().readAllBytes(),
            StandardCharsets.UTF_8).replace("\r\n", "\n").trim();
        assertTrue("OTP did not finish for " + moduleName + ": " + output,
            process.waitFor(60, TimeUnit.SECONDS));
        assertEquals("OTP rejected " + moduleName + ": " + output, 0, process.exitValue());
        assertEquals("OTP " + moduleName + " did not return ok: " + output,
            "ok", output);
    }

    private static List<Path> sources() throws Exception {
        File[] files = SOURCES.toFile().listFiles(
            file -> file.getName().endsWith(".erl"));
        Arrays.sort(files);
        List<Path> result = new ArrayList<>();
        for (File file : files) {
            result.add(file.toPath());
        }
        return result;
    }

}
