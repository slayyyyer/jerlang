package example;

import java.nio.file.Files;
import java.nio.file.Path;

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
 * <p>Needs an Erlang/OTP installation for the code path:
 *
 * <pre>
 * gradle run -PotpLib="C:/Program Files/Erlang OTP/lib"
 * </pre>
 *
 * <p>The option names the OTP root; this example takes its {@code lib}
 * directory on the command line and derives the root from it.
 */
public final class Supervised {

    private static final String EBIN = "jerlang.example.ebin";
    private static final String OTP_LIB = "jerlang.example.otpLib";
    private static final String THREADS = "jerlang.example.threads";

    public static void main(String[] args) throws Exception {
        String ebin = System.getProperty(EBIN, "build/ebin");
        Path otpLib = Path.of(System.getProperty(OTP_LIB, ""));
        int threads = Integer.getInteger(THREADS, Math.max(2, Runtime.getRuntime().availableProcessors()));

        if (!Files.isDirectory(otpLib)) {
            throw new IllegalStateException(
                "pass -DotpLib=<otp>/lib, or gradle run -PotpLib=<otp>/lib");
        }
        Path otpRoot = otpLib.getParent();
        if (otpRoot == null) {
            throw new IllegalStateException("not an OTP lib directory: " + otpLib);
        }

        Node node = Node.start(NodeOptions.builder().schedulers(threads)
            .codePath(Path.of(ebin).toAbsolutePath())
            .otpRoot(otpRoot)
            .build());
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
