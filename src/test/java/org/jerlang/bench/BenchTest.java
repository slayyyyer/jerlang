package org.jerlang.bench;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

import org.jerlang.FunctionSignature;
import org.jerlang.Module;
import org.jerlang.ModuleLoader;
import org.jerlang.ModuleRegistry;
import org.jerlang.Process;
import org.jerlang.ProcessOrPort;
import org.jerlang.ProcessRegistry;
import org.jerlang.erts.Erlang;
import org.jerlang.type.Atom;
import org.jerlang.type.Binary;
import org.jerlang.type.Integer;
import org.jerlang.type.Term;
import org.jerlang.type.Tuple;
import org.jerlang.vm.VirtualMachine;
import org.junit.After;
import org.junit.Assume;
import org.junit.Before;
import org.junit.Test;

/**
 * The benchmark baseline the roadmap asks for, so a later performance claim has
 * something to be measured against.
 *
 * This is not JMH and does not claim its precision. Each workload runs a
 * warmup, then a measured batch, repeated a few times; the reported number is
 * the median ns/op of the measured batches. Results are printed, never
 * asserted, because a shared CI runner can not carry a budget.
 *
 * Run it with:
 *
 * <pre>
 * mvn -Dtest=BenchTest -Djerlang.bench=true test
 * </pre>
 *
 * The interpreter workloads run inline: a {@link Process} is bound to the
 * benchmark thread and {@link Module#apply} then executes opcodes on that
 * thread. That removes process spawn and scheduler hand-off from the
 * measurement, so the number is the interpreter, not the harness. The message
 * workload deliberately keeps both, because that path is what an embedding
 * caller pays.
 */
public class BenchTest {

    private static final Path BEAMS = Paths.get("src/test/resources");
    private static final int REPEATS = 5;

    /** Keeps a result reachable so the JIT can not delete a workload. */
    private static Term sink;

    private final List<Path> copied = new ArrayList<>();

    @Before
    public void requireOptIn() {
        Assume.assumeTrue("set -Djerlang.bench=true to run the benchmarks",
            Boolean.getBoolean("jerlang.bench"));
    }

    @After
    public void cleanup() throws Exception {
        for (Path path : copied) {
            Files.deleteIfExists(path);
        }
        copied.clear();
    }

    /**
     * The interpreter resolves a module by name through the registry, so a
     * benchmark module has to be reachable the same way a user's would be: a
     * BEAM file in the working directory. Copy the fixtures in, as the other
     * tests do.
     */
    @Before
    public void copyFixtures() throws Exception {
        if (!Boolean.getBoolean("jerlang.bench")) {
            return;
        }
        for (String module : new String[] { "compare_demo", "constructs_demo",
            "funs_demo", "float", "bignum" }) {
            Path source = BEAMS.resolve(module + ".beam");
            if (Files.isRegularFile(source)) {
                Path local = Paths.get(module + ".beam");
                Files.copy(source, local, StandardCopyOption.REPLACE_EXISTING);
                copied.add(local);
            }
        }
    }

    @Test
    public void testBenchmarks() throws Throwable {
        List<Row> rows = new ArrayList<>();

        rows.add(moduleLoad("module load (constructs_demo.beam)"));
        rows.add(inline("function call", "compare_demo", "lt",
            new Term[] { Integer.of(1), Integer.of(2) }, 20_000, 100_000));
        rows.add(inline("guard dispatch", "compare_demo", "guard_lt",
            new Term[] { Integer.of(1), Integer.of(2) }, 20_000, 100_000));
        rows.add(inline("list head (get_hd)", "constructs_demo", "heads",
            new Term[] { org.jerlang.type.List.of(Integer.of(1), Integer.of(2)) },
            20_000, 100_000));
        rows.add(inline("binary construct", "constructs_demo", "bin",
            new Term[] { Integer.of(7), Binary.of(new byte[] { (byte) 255 }) },
            20_000, 100_000));
        rows.add(inline("record update", "constructs_demo", "update",
            new Term[] { record(), Integer.of(1) }, 20_000, 100_000));
        rows.add(inline("float binary segment", "constructs_demo", "nums",
            new Term[] { Integer.of(-2), org.jerlang.type.Float.of(1.5) },
            20_000, 100_000));
        rows.add(inline("big integer", "bignum", "test",
            new Term[0], 10_000, 50_000));
        rows.add(inline("closures and higher order", "funs_demo", "run",
            new Term[0], 5_000, 20_000));
        rows.add(builtin("lists:reverse/1 (100 elements)"));

        report(rows);
    }

    @Test
    public void testProcessRoundTrip() throws Throwable {
        Path beam = BEAMS.resolve("recv.beam");
        assumeExists(beam);
        Path local = Paths.get("recv.beam");
        Files.copy(beam, local, StandardCopyOption.REPLACE_EXISTING);
        copied.add(local);

        VirtualMachine.instance().start();
        try {
            List<Row> rows = new ArrayList<>();
            rows.add(crossBoundary("Java to Erlang call (spawn + return)",
                "compare_demo", "lt", new Term[] { Integer.of(1), Integer.of(2) },
                Atom.of("true"), 5, 3));
            rows.add(roundTrip());
            report(rows);
        } finally {
            VirtualMachine.instance().shutdown();
        }
    }

    /**
     * A plain call across the boundary, with no Erlang messages involved. The
     * cost is the scheduler's idle wakeup plus the caller's wait, so this row
     * is the floor of every other cross-boundary call.
     */
    private Row crossBoundary(String label, String moduleName, String function,
        Term[] arguments, Term expected, int ops, int repeats) throws Throwable {
        Atom module = Atom.of(moduleName);
        Atom fun = Atom.of(function);
        org.jerlang.type.List params = org.jerlang.type.List.of(arguments);

        sink = Erlang.apply(module, fun, params);
        assertEquals(expected, sink);

        double[] samples = new double[repeats];
        for (int repeat = 0; repeat < repeats; repeat++) {
            long start = System.nanoTime();
            for (int index = 0; index < ops; index++) {
                sink = Erlang.apply(module, fun, params);
            }
            samples[repeat] = (double) (System.nanoTime() - start) / ops;
        }
        assertEquals(expected, sink);
        return new Row(label, median(samples), 0);
    }

    /**
     * The embedding path: a Java thread calls an exported Erlang function, the
     * process is spawned on a scheduler, sends and receives messages, and the
     * caller waits for the result. This is the only workload where scheduler
     * hand-off is part of the number.
     */
    private Row roundTrip() throws Throwable {
        int warmup = 2;
        int ops = 5;
        int repeats = 3;
        Atom module = Atom.of("recv");
        Atom function = Atom.of("test");

        for (int index = 0; index < warmup; index++) {
            sink = Erlang.apply(module, function, org.jerlang.type.List.nil);
        }
        assertEquals(Integer.of(246), sink);

        double[] samples = new double[repeats];
        for (int repeat = 0; repeat < repeats; repeat++) {
            long start = System.nanoTime();
            for (int index = 0; index < ops; index++) {
                sink = Erlang.apply(module, function, org.jerlang.type.List.nil);
            }
            samples[repeat] = (double) (System.nanoTime() - start) / ops;
        }
        assertEquals(Integer.of(246), sink);
        return new Row("Java to Erlang round trip (spawn + 2 messages)",
            median(samples), 0);
    }

    /**
     * Load one BEAM file end to end: chunk parsing, instruction decode and
     * export. Repeated so the number is not a single cold read.
     */
    private Row moduleLoad(String label) throws Exception {
        Path beam = BEAMS.resolve("constructs_demo.beam");
        assumeExists(beam);
        Atom name = Atom.of("constructs_demo");

        Module warm = ModuleLoader.load(name, beam.toFile());
        assertNotNull("could not load constructs_demo.beam", warm);

        int ops = 200;
        double[] samples = new double[REPEATS];
        for (int repeat = 0; repeat < REPEATS; repeat++) {
            long start = System.nanoTime();
            for (int index = 0; index < ops; index++) {
                Module module = ModuleLoader.load(name, beam.toFile());
                if (module == null) {
                    throw new IllegalStateException("load failed");
                }
                sink = Integer.of(module.beamData().codeChunk().instructions().size());
            }
            samples[repeat] = (double) (System.nanoTime() - start) / ops;
        }
        return new Row(label, median(samples), 0);
    }

    private Row inline(String label, String moduleName, String function,
        Term[] arguments, int warmup, int ops) throws Throwable {
        Path beam = BEAMS.resolve(moduleName + ".beam");
        assumeExists(beam);
        Atom moduleAtom = Atom.of(moduleName);
        Module module = ModuleRegistry.get(moduleAtom);
        assertNotNull("could not load " + moduleName + " from the working directory", module);

        FunctionSignature signature = new FunctionSignature(moduleAtom,
            Atom.of(function), Integer.of(arguments.length));
        org.jerlang.type.List params = org.jerlang.type.List.of(arguments);

        int frames = 0;
        double[] samples = new double[REPEATS];
        for (int repeat = 0; repeat < REPEATS; repeat++) {
            Process process = new Process(new PID(), moduleAtom, Atom.of(function), params);
            ProcessOrPort previous = ProcessRegistry.self();
            ProcessRegistry.self(process);
            try {
                for (int index = 0; index < warmup; index++) {
                    sink = module.apply(signature, params);
                }
                int before = process.framesDepth();
                long start = System.nanoTime();
                for (int index = 0; index < ops; index++) {
                    sink = module.apply(signature, params);
                }
                long elapsed = System.nanoTime() - start;
                frames = process.framesDepth() - before;
                samples[repeat] = (double) elapsed / ops;
            } finally {
                ProcessRegistry.self(previous);
            }
        }
        assertNotNull("workload " + label + " produced no value", sink);
        return new Row(label, median(samples), frames);
    }

    private Row builtin(String label) throws Throwable {
        Atom moduleAtom = Atom.of("lists");
        Module module = ModuleRegistry.get(moduleAtom);
        assertNotNull("no built-in lists module", module);

        org.jerlang.type.List numbers = org.jerlang.type.List.nil;
        for (int value = 100; value >= 1; value--) {
            numbers = new org.jerlang.type.List(Integer.of(value), numbers);
        }
        FunctionSignature signature = new FunctionSignature(moduleAtom,
            Atom.of("reverse"), Integer.of(1));
        org.jerlang.type.List params = org.jerlang.type.List.of(numbers);

        int ops = 20_000;
        double[] samples = new double[REPEATS];
        for (int repeat = 0; repeat < REPEATS; repeat++) {
            Process process = new Process(new PID(), moduleAtom, Atom.of("reverse"), params);
            ProcessOrPort previous = ProcessRegistry.self();
            ProcessRegistry.self(process);
            try {
                for (int index = 0; index < 2_000; index++) {
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
        return new Row(label, median(samples), 0);
    }

    private static Tuple record() {
        return Tuple.of(Atom.of("r"), Integer.of(0), Integer.of(2), Integer.of(3));
    }

    private static void assumeExists(Path beam) {
        Assume.assumeTrue("missing " + beam + "; compile the Erlang test modules first",
            Files.isRegularFile(beam));
    }

    private static double median(double[] samples) {
        double[] sorted = samples.clone();
        Arrays.sort(sorted);
        return sorted[sorted.length / 2];
    }

    private static void report(List<Row> rows) {
        System.out.println();
        System.out.println("JErlang benchmark baseline");
        System.out.println("JDK " + System.getProperty("java.version")
            + ", " + Runtime.getRuntime().availableProcessors() + " processors"
            + ", " + System.getProperty("os.name"));
        System.out.println("median ns/op over " + REPEATS
            + " measured batches after warmup; frames = stack depth change per call");
        System.out.println();
        System.out.printf(Locale.ROOT, "%-42s %12s %14s %8s%n",
            "workload", "ns/op", "ops/sec", "frames");
        System.out.println("-".repeat(80));
        for (Row row : rows) {
            System.out.printf(Locale.ROOT, "%-42s %12.1f %14s %8d%n",
                row.label, row.nanos, String.format(Locale.ROOT, "%,.0f", 1e9 / row.nanos),
                row.frames);
        }
        System.out.println();
    }

    private static final class Row {

        final String label;
        final double nanos;
        final int frames;

        Row(String label, double nanos, int frames) {
            this.label = label;
            this.nanos = nanos;
            this.frames = frames;
        }

    }

    /** A PID for a benchmark process that is never registered or scheduled. */
    private static final class PID extends org.jerlang.type.PID {

        PID() {
            super(VirtualMachine.instance().node(), 900_000, 0,
                VirtualMachine.instance().nodeCreation());
        }

    }

}
