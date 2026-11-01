package org.jerlang.bench;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import org.jerlang.FunctionSignature;
import org.jerlang.Module;
import org.jerlang.ModuleLoader;
import org.jerlang.ModuleRegistry;
import org.jerlang.Process;
import org.jerlang.ProcessOrPort;
import org.jerlang.ProcessRegistry;
import org.jerlang.type.Atom;
import org.jerlang.type.Integer;
import org.jerlang.type.Term;
import org.jerlang.vm.VirtualMachine;
import org.junit.After;
import org.junit.Assume;
import org.junit.Before;
import org.junit.Test;

/**
 * The benchmark comparison the 1.0 exit test asks for: the same Erlang
 * workloads measured on this runtime and on OTP 29, on the same machine, with
 * both numbers published.
 *
 * The workloads live in {@code src/test/erl/bench/beam_bench.erl}. This test
 * compiles it with {@code erlc}, runs its {@code main/1} on a real node to get
 * the BEAM column, then loads the same {@code .beam} file into this runtime and
 * times the same exported functions to get the JErlang column. Nothing is
 * written twice: a workload that exists on only one side fails the test.
 *
 * <pre>
 * mvn -Dtest=BeamBenchTest -Djerlang.bench=true \
 *     -Djerlang.otp.dir=/path/to/otp test
 * </pre>
 *
 * Results are printed, not asserted, except for the message throughput budget
 * from the roadmap, which is enforced when {@code -Djerlang.bench.gate=true}:
 * no more than {@code -Djerlang.bench.max.ratio} (5 by default) times BEAM. The
 * gate is opt-in so a developer running the benchmarks on a loaded laptop still
 * gets the table; CI runs it with the gate on, where the number is a claim.
 */
public class BeamBenchTest {

    /**
     * Label, exported function and batch size, matching
     * beam_bench:workloads/0. The labels are what BEAM prints and what the
     * table below is keyed on, so a workload renamed on one side fails the
     * name comparison instead of being silently compared against a different
     * row.
     */
    private static final String[][] WORKLOADS = {
        { "function_call", "lt", "200000" },
        { "guard_dispatch", "guard_lt", "200000" },
        { "list_head", "heads", "200000" },
        { "binary_construct", "bin", "200000" },
        { "record_update", "update", "200000" },
        { "float_binary_segment", "nums", "200000" },
        { "big_integer", "bignum", "50000" },
        { "closures_and_higher_order", "closures", "20000" },
        { "lists_reverse_100", "reverse100", "20000" },
        { "message_round_trip", "msgpong", "2000" },
        { "ets_insert_and_lookup", "ets_round", "2000" },
    };

    private static final int REPEATS = 5;

    /** Keeps a measured value reachable so the JIT can not delete a workload. */
    private static Term sink;

    private Path work;

    @After
    public void cleanup() throws IOException {
        if (work != null) {
            Files.deleteIfExists(work.resolve("beam_bench.beam"));
        }
    }

    @Before
    public void requireOptIn() {
        Assume.assumeTrue("set -Djerlang.bench=true to run the benchmarks",
            Boolean.getBoolean("jerlang.bench"));
    }

    @Test
    public void compareAgainstBeam() throws Throwable {
        File erl = find("erl");
        File erlc = find("erlc");
        Assume.assumeTrue("needs an Erlang installation: erl and erlc on PATH "
            + "or -Djerlang.otp.dir pointing at one", erl != null && erlc != null);

        // erlc writes the .beam into the working directory, which is the first
        // place the module loader looks: the same path the fixtures use.
        work = Paths.get("").toAbsolutePath();
        Path source = Paths.get("src/test/erl/bench/beam_bench.erl");
        Assume.assumeTrue("missing " + source, Files.isRegularFile(source));

        exec(Arrays.asList(erlc.getAbsolutePath(), "-o", work.toString(),
            source.toAbsolutePath().toString()));

        Map<String, Double> beam = runOnBeam(erl);
        Map<String, Double> jerlang = runOnJerlang();
        double beamStartup = startupOfBeam(erl);
        double jerlangStartup = startupOfJvm();

        assertEquals("workload names differ: BEAM ran " + beam.keySet()
            + ", JErlang ran " + jerlang.keySet(), beam.keySet(), jerlang.keySet());

        report(beam, jerlang, beamStartup, jerlangStartup);
    }

    /** Runs beam_bench:main/1 on a real node and parses its BENCH lines. */
    private Map<String, Double> runOnBeam(File erl) throws Exception {
        // -eval, not -s or -run: those call main/0 and main([Atom]) respectively
        // and the entry point takes an argument list. The expression has no
        // double quotes, which is what makes it safe to pass on Windows.
        String output = exec(Arrays.asList(erl.getAbsolutePath(), "-noshell",
            "-pa", work.toString(), "-eval", "beam_bench:main([])"));
        Map<String, Double> rows = new LinkedHashMap<>();
        for (String line : output.split("\\R")) {
            String[] parts = line.trim().split("\\t");
            if (parts.length == 3 && "BENCH".equals(parts[0])) {
                rows.put(parts[1], Double.valueOf(parts[2]));
            }
        }
        Assume.assumeTrue("BEAM produced no benchmark rows:\n" + output, !rows.isEmpty());
        return rows;
    }

    /**
     * The same functions, interpreted. A process is bound to this thread so
     * {@link Module#apply} runs opcodes here: the column is the interpreter,
     * with no scheduler hand-off, which is what the BEAM column measures too.
     */
    private Map<String, Double> runOnJerlang() throws Throwable {
        Atom moduleName = Atom.of("beam_bench");
        Module module = ModuleLoader.load(moduleName,
            work.resolve("beam_bench.beam").toFile());
        Assume.assumeTrue("could not load beam_bench.beam", module != null);
        // Registered as well as loaded: the module's own calls go through the
        // registry, and a second instance without the export table is what
        // turns an interpreted call into {undef}.
        ModuleRegistry.register(module);

        Map<String, Double> rows = new LinkedHashMap<>();
        for (String[] workload : WORKLOADS) {
            Atom function = Atom.of(workload[1]);
            int ops = java.lang.Integer.parseInt(workload[2]);
            // The signature comes from the module's own export table rather
            // than being rebuilt here: the table is what the interpreter
            // looks a call up in, so using its instance removes any question
            // of two equal-but-different keys.
            FunctionSignature signature = export(module, function, 0);
            org.jerlang.type.List params = org.jerlang.type.List.nil;

            int warmup = Math.max(1, Math.min(1000, ops / 10));
            double[] samples = new double[REPEATS];
            // Name the workload in the failure: an {undef} coming out of the
            // interpreter says nothing about which row produced it.
            try {
            for (int repeat = 0; repeat < REPEATS; repeat++) {
                Process process = new Process(new BenchPID(), moduleName, function, params);
                ProcessOrPort previous = ProcessRegistry.self();
                ProcessRegistry.self(process);
                try {
                    for (int index = 0; index < warmup; index++) {
                        sink = module.apply(signature, params);
                    }
                    long start = System.nanoTime();
                    for (int index = 0; index < ops; index++) {
                        sink = module.apply(signature, params);
                    }
                    samples[repeat] = (double) (System.nanoTime() - start) / ops;
                } finally {
                    ProcessRegistry.self(previous);
                }
            }
            } catch (Throwable failure) {
                throw new AssertionError("workload " + workload[0] + " failed: "
                    + failure + " (exported: " + module.hasFunction(signature)
                    + ", file: " + work.resolve("beam_bench.beam")
                    + ", exports: " + module.beamData().exportTableChunk().exports()
                    + ")",
                    failure);
            }
            rows.put(workload[0], median(samples));
        }
        return rows;
    }

    /** Wall clock for a real node to boot and stop, JVM excluded. */
    private static FunctionSignature export(Module module, Atom function, int arity) {
        for (FunctionSignature candidate : module.beamData().exportTableChunk().exports()) {
            if (candidate.function().equals(function)
                && candidate.fun_arity().equals(Integer.of(arity))) {
                return candidate;
            }
        }
        throw new AssertionError("beam_bench has no export " + function + "/" + arity
            + "; exports are " + module.beamData().exportTableChunk().exports());
    }

    private double startupOfBeam(File erl) throws Exception {
        double[] samples = new double[3];
        for (int index = 0; index < samples.length; index++) {
            long start = System.nanoTime();
            exec(Arrays.asList(erl.getAbsolutePath(), "-noshell", "-s", "init", "stop"));
            samples[index] = (System.nanoTime() - start) / 1e6;
        }
        return median(samples);
    }

    /** Wall clock for a forked JVM to boot, start a node and close it. */
    private double startupOfJvm() throws Exception {
        String java = Paths.get(System.getProperty("java.home"), "bin", "java").toString();
        String classpath = System.getProperty("java.class.path");
        double[] samples = new double[3];
        for (int index = 0; index < samples.length; index++) {
            long start = System.nanoTime();
            exec(Arrays.asList(java, "-cp", classpath,
                StartupProbe.class.getName()));
            samples[index] = (System.nanoTime() - start) / 1e6;
        }
        return median(samples);
    }

    private void report(Map<String, Double> beam, Map<String, Double> jerlang,
        double beamStartup, double jerlangStartup) {
        System.out.println();
        System.out.println("JErlang against BEAM, same machine, same Erlang source");
        System.out.println("JDK " + System.getProperty("java.version")
            + ", " + Runtime.getRuntime().availableProcessors() + " processors"
            + ", " + System.getProperty("os.name"));
        System.out.println("median of " + REPEATS + " batches after warmup");
        System.out.println();
        System.out.printf(Locale.ROOT, "%-30s %14s %14s %8s%n",
            "workload", "jerlang ns/op", "BEAM ns/op", "ratio");
        System.out.println("-".repeat(70));

        double worst = 0;
        String worstName = "";
        for (Map.Entry<String, Double> row : jerlang.entrySet()) {
            double nativeNanos = beam.get(row.getKey());
            double ratio = row.getValue() / nativeNanos;
            if (ratio > worst) {
                worst = ratio;
                worstName = row.getKey();
            }
            System.out.printf(Locale.ROOT, "%-30s %14.1f %14.1f %7.2fx%n",
                row.getKey(), row.getValue(), nativeNanos, ratio);
        }

        System.out.printf(Locale.ROOT, "%-30s %14.1f %14.1f %7.2fx%n",
            "startup (process wall ms)", jerlangStartup, beamStartup,
            jerlangStartup / beamStartup);
        System.out.println();
        System.out.printf(Locale.ROOT,
            "BENCH SUMMARY worst=%.2fx (%s) message_round_trip=%.2fx%n",
            worst, worstName, jerlang.get("message_round_trip")
                / beam.get("message_round_trip"));

        if (Boolean.getBoolean("jerlang.bench.gate")) {
            double budget = Double.parseDouble(
                System.getProperty("jerlang.bench.max.ratio", "5.0"));
            double ratio = jerlang.get("message_round_trip") / beam.get("message_round_trip");
            org.junit.Assert.assertTrue("message throughput budget: " + ratio
                + "x BEAM exceeds the " + budget + "x budget from docs/benchmarks.md",
                ratio <= budget);
        }
    }

    private static void assertEquals(String message, Object expected, Object actual) {
        org.junit.Assert.assertEquals(message, expected, actual);
    }

    /**
     * erl and erlc from -Djerlang.otp.dir/bin when it is set, otherwise from
     * PATH. A missing installation skips the test rather than failing it, the
     * same rule the other OTP-backed tests follow.
     */
    private static File find(String executable) {
        String otpDir = System.getProperty("jerlang.otp.dir");
        if (otpDir != null && !otpDir.isEmpty()) {
            for (String candidate : new String[] { executable, executable + ".exe" }) {
                File file = new File(new File(otpDir, "bin"), candidate);
                if (file.isFile()) {
                    return file;
                }
            }
        }
        for (String directory : System.getenv("PATH").split(File.pathSeparator)) {
            for (String candidate : new String[] { executable, executable + ".exe" }) {
                File file = new File(directory, candidate);
                if (file.isFile()) {
                    return file;
                }
            }
        }
        return null;
    }

    private static String exec(List<String> command) throws IOException, InterruptedException {
        ProcessBuilder builder = new ProcessBuilder(command);
        builder.redirectErrorStream(true);
        java.lang.Process process = builder.start();
        StringBuilder output = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(
            new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                output.append(line).append('\n');
            }
        }
        boolean finished = process.waitFor(10, TimeUnit.MINUTES);
        if (!finished) {
            process.destroyForcibly();
            throw new IllegalStateException("timed out: " + String.join(" ", command));
        }
        if (!command.isEmpty() && command.get(0).endsWith("erlc")
            && process.exitValue() != 0) {
            throw new IllegalStateException("erlc failed:\n" + output);
        }
        return output.toString();
    }

    private static double median(double[] samples) {
        double[] sorted = samples.clone();
        Arrays.sort(sorted);
        return sorted[sorted.length / 2];
    }

    /** A PID for a benchmark process that is never registered or scheduled. */
    private static final class BenchPID extends org.jerlang.type.PID {

        BenchPID() {
            super(VirtualMachine.instance().node(), 910_000, 0,
                VirtualMachine.instance().nodeCreation());
        }

    }

}
