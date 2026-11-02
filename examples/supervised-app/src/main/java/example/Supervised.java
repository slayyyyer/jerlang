package example;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.jerlang.CodePaths;
import org.jerlang.ModuleRegistry;
import org.jerlang.api.Node;
import org.jerlang.api.NodeOptions;
import org.jerlang.type.Atom;
import org.jerlang.type.Term;

/**
 * Starts an OTP application from Java, kills its worker, and shows the supervisor
 * putting it back.
 *
 * <p>What runs here is OTP's own {@code application} controller and
 * {@code supervisor} module, loaded from the Erlang installation. JErlang supplies
 * the processes, messages and timers they need; it does not ship a second copy of
 * the behaviours.
 *
 * <p>Needs an Erlang/OTP installation for the code path:
 *
 * <pre>
 * gradle run -PotpLib="C:/Program Files/Erlang OTP/lib"
 * </pre>
 */
public final class Supervised {

    private static final String EBIN = "jerlang.example.ebin";
    private static final String OTP_LIB = "jerlang.example.otpLib";
    private static final String THREADS = "jerlang.example.threads";

    public static void main(String[] args) throws Exception {
        String ebin = System.getProperty(EBIN, "build/ebin");
        String otpLib = System.getProperty(OTP_LIB, "");
        int threads = Integer.getInteger(THREADS, Math.max(2, Runtime.getRuntime().availableProcessors()));

        CodePaths.reset();
        ModuleRegistry.reset();
        for (Path directory : otpEbins(otpLib)) {
            CodePaths.addPatha(CodePaths.normalizePath(directory.toString()));
        }
        CodePaths.addPatha(CodePaths.normalizePath(Path.of(ebin).toAbsolutePath().toString()));

        Node node = Node.start(NodeOptions.builder().schedulers(threads).build());
        // Idempotent so a Ctrl-C after the explicit close below is harmless.
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            try {
                node.close();
            } catch (RuntimeException alreadyClosed) {
                // closing twice is fine
            }
        }, "supervised-shutdown"));

        System.out.println("application:start(demo) -> " + call(node, "application", "start", Atom.of("demo")));
        System.out.println("demo_worker:bump()      -> " + call(node, "demo_worker", "bump"));
        System.out.println("demo_worker:bump()      -> " + call(node, "demo_worker", "bump"));
        System.out.println("demo_worker:count()     -> " + call(node, "demo_worker", "count"));

        String before = children(node);
        System.out.println("children before crash   -> " + before);

        System.out.println("demo_worker:crash()     -> " + call(node, "demo_worker", "crash"));
        Thread.sleep(500);

        String after = children(node);
        System.out.println("children after crash    -> " + after);
        System.out.println("worker restarted        -> " + !before.equals(after));
        System.out.println("demo_worker:count()     -> " + call(node, "demo_worker", "count")
            + "   (a fresh process starts at zero)");

        System.out.println("application:stop(demo)  -> " + call(node, "application", "stop", Atom.of("demo")));
        System.out.println("whereis(demo_sup)       -> " + call(node, "erlang", "whereis", Atom.of("demo_sup")));

        // Schedulers are non-daemon threads: without this the JVM stays up with
        // nothing to do, which is the one thing an embedding application must not
        // leave to the shutdown hook.
        node.close();
    }

    /** kernel and stdlib are what the application controller and supervisor need. */
    private static List<Path> otpEbins(String otpLib) throws IOException {
        if (otpLib == null || otpLib.isEmpty()) {
            throw new IllegalStateException(
                "pass -DotpLib=<otp>/lib, or gradle run -PotpLib=<otp>/lib");
        }
        Path lib = Path.of(otpLib);
        if (!Files.isDirectory(lib)) {
            throw new IllegalStateException("not a directory: " + lib);
        }
        List<Path> ebins = new ArrayList<>();
        try (DirectoryStream<Path> apps = Files.newDirectoryStream(lib)) {
            for (Path app : apps) {
                String name = app.getFileName().toString();
                if (name.startsWith("kernel-") || name.startsWith("stdlib-")) {
                    Path ebin = app.resolve("ebin");
                    if (Files.isDirectory(ebin)) {
                        ebins.add(ebin);
                    }
                }
            }
        }
        if (ebins.size() < 2) {
            throw new IllegalStateException("kernel and stdlib not found under " + lib);
        }
        return ebins;
    }

    /**
     * The supervisor's answer, printed as a term: it contains the child's pid, so
     * a restart is a visible difference between two lines.
     */
    private static String children(Node node) {
        return String.valueOf(node.apply("supervisor", "which_children", Atom.of("demo_sup")));
    }

    private static Term call(Node node, String module, String function, Term... arguments) {
        return node.apply(module, function, arguments);
    }
}
