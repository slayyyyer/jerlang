package example;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.jerlang.api.Bridge;
import org.jerlang.api.Node;
import org.jerlang.api.NodeOptions;
import org.jerlang.erts.Emulator;
import org.jerlang.type.Atom;
import org.jerlang.type.Str;
import org.jerlang.type.Term;

/**
 * A mixed cluster: an OTP node and a JErlang node on the same host, with
 * {@code erpc:call/4} travelling in both directions and a Java caller making
 * the local call.
 *
 * <p>Run with OTP on {@code PATH} (the build already compiles the peer with
 * {@code erlc}):
 *
 * <pre>
 * gradle run
 * </pre>
 */
public final class MixedCluster {

    private static final String COOKIE = "mixed-cluster-cookie";
    private static final ExecutorService CONSOLE = Executors.newSingleThreadExecutor(task -> {
        Thread thread = new Thread(task, "otp-console");
        thread.setDaemon(true);
        return thread;
    });

    public static void main(String[] args) throws Exception {
        String ebin = System.getProperty("jerlang.example.ebin", "build/ebin");
        String otpEbin = System.getProperty("jerlang.example.otpEbin", "build/otp");

        // EPMD is the name registry both runtimes use. Starting it explicitly
        // makes the order of the two nodes irrelevant.
        startEpmd();

        Emulator.parse(new String[] { "-sname", "mixed_jerlang", "-setcookie", COOKIE });
        Node node = Node.start(NodeOptions.builder()
            .codePath(Path.of(ebin).toAbsolutePath())
            .build());
        Process peer = null;
        try {
            // This is the function OTP can reach over erpc. It has no special
            // protocol: it is an ordinary exported Erlang function.
            node.registerFunction("java_caller", "echo", 1,
                arguments -> arguments[0]);

            String local = node.name().toString();
            String host = local.substring(local.indexOf('@') + 1);
            String peerNode = "mixed_otp@" + host;
            System.out.println("JErlang node -> " + local);
            peer = startPeer(otpEbin, local);
            BufferedReader output = new BufferedReader(new InputStreamReader(
                peer.getInputStream(), StandardCharsets.UTF_8));

            String ready = readUntil(output, "READY ", 30);
            System.out.println("OTP peer ready -> " + ready.substring(ready.indexOf("READY ")));

            Term fromOtp = node.apply("mixed_jerlang", "echo", Str.of("from-java"));
            System.out.println("Java -> JErlang -> " + Bridge.toJava(fromOtp));

            Term toOtp = node.apply("mixed_jerlang", "call_peer",
                Atom.of(peerNode), Str.of("from-jerlang"));
            System.out.println("Java -> JErlang -> erpc -> OTP -> " + Bridge.toJava(toOtp));

            String otpCall = readUntil(output, "OTP_TO_JERLANG", 30);
            System.out.println(otpCall);

            if (!otpCall.contains("from-otp")) {
                throw new IllegalStateException("OTP did not reach the JErlang callback: "
                    + otpCall);
            }
        } finally {
            if (peer != null) {
                peer.destroy();
                peer.waitFor(5, TimeUnit.SECONDS);
            }
            node.close();
        }
    }

    private static Process startPeer(String otpEbin, String localNode) throws IOException {
        String erl = System.getenv().getOrDefault("ERL", "erl");
        // -run, not -eval: the Windows command line strips embedded quotes
        // from an eval expression, while -run passes the node name as an
        // argument and leaves the Erlang source in mixed_peer.erl.
        ProcessBuilder builder = new ProcessBuilder(
            erl, "-sname", "mixed_otp", "-setcookie", COOKIE, "-noshell",
            "-pa", Path.of(otpEbin).toAbsolutePath().toString(),
            "-run", "mixed_peer", "boot", localNode);
        builder.redirectErrorStream(true);
        return builder.start();
    }

    private static void startEpmd() throws IOException, InterruptedException {
        String command = System.getenv().getOrDefault("EPMD", "epmd");
        Process epmd = new ProcessBuilder(command, "-daemon")
            .redirectErrorStream(true)
            .start();
        if (!epmd.waitFor(5, TimeUnit.SECONDS)) {
            epmd.destroy();
            throw new IllegalStateException("epmd did not start");
        }
    }

    /**
     * Read until a line contains the marker. OTP may print an informational
     * line first; buffering those here keeps a startup message from being
     * mistaken for the answer.
     */
    private static String readUntil(BufferedReader reader, String marker, int seconds)
        throws IOException {
        Future<String> read = CONSOLE.submit(() -> {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.contains(marker)) {
                    return line;
                }
                System.out.println("otp: " + line);
            }
            throw new IOException("OTP console closed before " + marker);
        });
        try {
            return read.get(seconds, TimeUnit.SECONDS);
        } catch (TimeoutException timeout) {
            read.cancel(true);
            throw new IllegalStateException("timed out waiting for " + marker, timeout);
        } catch (java.util.concurrent.ExecutionException failure) {
            throw new IllegalStateException("OTP console failed: " + failure.getCause(), failure);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted waiting for " + marker, interrupted);
        }
    }

}
