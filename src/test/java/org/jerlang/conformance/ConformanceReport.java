package org.jerlang.conformance;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Stream;

import org.jerlang.Module;
import org.jerlang.ModuleLoader;
import org.jerlang.erts.emulator.Instruction;
import org.jerlang.type.Atom;
import org.jerlang.util.ByteUtil;

/**
 * The conformance pass rate: what fraction of a directory of BEAM files this
 * runtime can load, and what fraction of those use only opcodes that have a
 * handler.
 *
 * Opcode counts are not the metric. A module counts as a pass only when it
 * loads and every instruction in it has a handler, because both are required
 * before any of its code can run:
 *
 * <ul>
 * <li>{@link Status#LOAD_FAILED} — the loader rejected the file. That is an
 *     unknown opcode number, a chunk this loader can not read, or a file that
 *     is not a BEAM file at all.</li>
 * <li>{@link Status#OPCODE_GAP} — the module loaded, but at least one
 *     instruction has no handler, so calling into that function stops with
 *     "Unsupported opcode". The gap is named, which is the allowed form for
 *     milestone 0.5.</li>
 * <li>{@link Status#OK} — loaded, and every instruction has a handler.</li>
 * </ul>
 *
 * Loading is read-only: nothing is registered in {@link org.jerlang.ModuleRegistry},
 * so a run over a few hundred unrelated OTP modules does not install them.
 * Execution is deliberately not part of the metric: calling arbitrary exported
 * functions of arbitrary OTP modules has side effects and needs the standard
 * library, which is milestone 0.6.
 */
public final class ConformanceReport {

    public enum Status {
        OK,
        OPCODE_GAP,
        LOAD_FAILED
    }

    /**
     * The outcome for one BEAM file.
     */
    public static final class ModuleResult {

        public final String name;
        public final String file;
        public final Status status;
        public final int exports;
        public final int instructions;
        /** Opcode name to number of instructions, for opcodes with no handler. */
        public final Map<String, Integer> gaps;
        /** Why the file was rejected, or "" when it loaded. */
        public final String detail;

        ModuleResult(String name, String file, Status status, int exports,
            int instructions, Map<String, Integer> gaps, String detail) {
            this.name = name;
            this.file = file;
            this.status = status;
            this.exports = exports;
            this.instructions = instructions;
            this.gaps = Collections.unmodifiableMap(gaps);
            this.detail = detail;
        }

        public String describe() {
            StringBuilder line = new StringBuilder();
            line.append(name).append(' ').append(status);
            if (status == Status.OK) {
                line.append(" (").append(exports).append(" exports, ")
                    .append(instructions).append(" instructions)");
            } else if (status == Status.OPCODE_GAP) {
                line.append(" ").append(gaps);
            } else {
                line.append(" ").append(detail);
            }
            return line.toString();
        }

    }

    /**
     * The outcome for a whole corpus.
     */
    public static final class Report {

        public final java.util.List<ModuleResult> modules;
        public final java.util.List<String> duplicates;

        Report(java.util.List<ModuleResult> modules, java.util.List<String> duplicates) {
            this.modules = Collections.unmodifiableList(modules);
            this.duplicates = Collections.unmodifiableList(duplicates);
        }

        public int total() {
            return modules.size();
        }

        public int count(Status status) {
            int count = 0;
            for (ModuleResult module : modules) {
                if (module.status == status) {
                    count++;
                }
            }
            return count;
        }

        public int loaded() {
            return total() - count(Status.LOAD_FAILED);
        }

        /** Fraction of modules that loaded and use only handled opcodes. */
        public double passRate() {
            return total() == 0 ? 0.0 : (double) count(Status.OK) / total();
        }

        public double loadRate() {
            return total() == 0 ? 0.0 : (double) loaded() / total();
        }

        public java.util.List<ModuleResult> failures() {
            java.util.List<ModuleResult> failures = new ArrayList<>();
            for (ModuleResult module : modules) {
                if (module.status != Status.OK) {
                    failures.add(module);
                }
            }
            return failures;
        }

        public String summary() {
            return String.format(Locale.ROOT,
                "conformance: %d modules, %d loaded (%.1f%%), %d passed (%.1f%%), "
                    + "%d opcode gaps, %d load failures",
                total(), loaded(), loadRate() * 100.0, count(Status.OK),
                passRate() * 100.0, count(Status.OPCODE_GAP),
                count(Status.LOAD_FAILED));
        }

        /**
         * One line per module, then the summary, then the failures in detail.
         * Stable order so two runs can be diffed.
         */
        public String render() {
            StringBuilder out = new StringBuilder();
            for (ModuleResult module : modules) {
                out.append(module.describe()).append('\n');
            }
            out.append(summary()).append('\n');
            if (!duplicates.isEmpty()) {
                out.append("duplicate module names (first file wins): ")
                    .append(duplicates).append('\n');
            }
            return out.toString();
        }

    }

    private ConformanceReport() {
    }

    /**
     * Inspect every .beam file below the given roots. A root may be a
     * directory (searched recursively) or a single file.
     */
    public static Report inspect(java.util.List<Path> roots) throws IOException {
        Map<String, Path> byName = new TreeMap<>();
        java.util.List<String> duplicates = new ArrayList<>();
        for (Path root : roots) {
            java.util.List<Path> files = new ArrayList<>();
            if (Files.isRegularFile(root)) {
                files.add(root);
            } else if (Files.isDirectory(root)) {
                try (Stream<Path> walk = Files.walk(root)) {
                    walk.filter(Files::isRegularFile)
                        .filter(path -> path.getFileName().toString().endsWith(".beam"))
                        .forEach(files::add);
                }
            } else {
                throw new IOException("no such corpus path: " + root);
            }
            Collections.sort(files);
            for (Path file : files) {
                String name = moduleName(file);
                if (byName.containsKey(name)) {
                    duplicates.add(name + " (" + file + ")");
                    continue;
                }
                byName.put(name, file);
            }
        }

        java.util.List<ModuleResult> results = new ArrayList<>();
        for (Map.Entry<String, Path> entry : byName.entrySet()) {
            results.add(inspectModule(entry.getKey(), entry.getValue()));
        }
        return new Report(results, duplicates);
    }

    public static Report inspect(Path... roots) throws IOException {
        return inspect(Arrays.asList(roots));
    }

    private static ModuleResult inspectModule(String name, Path file) {
        String path = file.toString();
        if (!isBeamFile(file)) {
            return new ModuleResult(name, path, Status.LOAD_FAILED, 0, 0,
                Collections.<String, Integer>emptyMap(), "not a BEAM file");
        }

        Module module;
        try {
            module = ModuleLoader.load(Atom.of(name), file.toFile());
        } catch (Throwable failure) {
            return new ModuleResult(name, path, Status.LOAD_FAILED, 0, 0,
                Collections.<String, Integer>emptyMap(), describe(failure));
        }
        if (module == null) {
            return new ModuleResult(name, path, Status.LOAD_FAILED, 0, 0,
                Collections.<String, Integer>emptyMap(), "loader rejected the file");
        }
        if (module.beamData() == null || module.beamData().codeChunk() == null) {
            return new ModuleResult(name, path, Status.LOAD_FAILED, 0, 0,
                Collections.<String, Integer>emptyMap(), "no code chunk");
        }

        java.util.List<Instruction> instructions =
            module.beamData().codeChunk().instructions();
        Map<String, Integer> gaps = new TreeMap<>();
        for (Instruction instruction : instructions) {
            if (instruction.opcode().methodHandle() == null) {
                String opcode = instruction.opcode().toString();
                gaps.put(opcode, gaps.containsKey(opcode) ? gaps.get(opcode) + 1 : 1);
            }
        }
        int exports = module.beamData().exportTableChunk() == null
            ? 0 : module.beamData().exportTableChunk().exports().size();
        Status status = gaps.isEmpty() ? Status.OK : Status.OPCODE_GAP;
        return new ModuleResult(name, path, status, exports, instructions.size(),
            gaps, "");
    }

    /**
     * The loader reads the chunk list from the file header, so a file that is
     * not a BEAM file fails there. Check the magic first so the report can say
     * which of the two it was. A checked-in BEAM file is often stored gzipped,
     * which the loader accepts, so look through the compression.
     */
    private static boolean isBeamFile(Path file) {
        byte[] header;
        try {
            header = ByteUtil.maybe_decompress(Files.readAllBytes(file));
        } catch (IOException e) {
            return false;
        }
        if (header.length < 12) {
            return false;
        }
        return header[0] == 'F' && header[1] == 'O' && header[2] == 'R' && header[3] == '1'
            && header[8] == 'B' && header[9] == 'E' && header[10] == 'A' && header[11] == 'M';
    }

    private static String describe(Throwable failure) {
        String message = failure.getMessage();
        String type = failure.getClass().getSimpleName();
        return message == null ? type : type + ": " + message;
    }

    private static String moduleName(Path file) {
        String filename = file.getFileName().toString();
        return filename.substring(0, filename.length() - ".beam".length());
    }

    /**
     * Run the metric over one or more directories:
     *
     * <pre>
     * java org.jerlang.conformance.ConformanceReport /path/to/otp/lib ...
     * </pre>
     */
    public static void main(String[] args) throws Exception {
        if (args.length == 0) {
            System.err.println("usage: ConformanceReport <directory-or-beam> ...");
            System.exit(2);
        }
        java.util.List<Path> roots = new ArrayList<>();
        for (String arg : args) {
            roots.add(Paths.get(arg));
        }
        Report report = inspect(roots);
        System.out.print(report.render());
    }

}
