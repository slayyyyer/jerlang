package example;

import java.nio.file.Path;

import org.jerlang.api.Bridge;
import org.jerlang.api.Node;
import org.jerlang.api.NodeOptions;
import org.jerlang.type.Integer;

/**
 * Million processes and a message-passing ring on a JErlang node.
 *
 * <p>The Erlang source is shared with the BEAM column. The only difference is
 * who starts the process and reads the clock: here it is a Java thread, on BEAM
 * it is a separate Erlang invocation.
 */
public final class ConcurrencyDemo {

    public static void main(String[] args) {
        int spawns = args.length > 0 ? java.lang.Integer.parseInt(args[0]) : 1_000_000;
        int ringProcesses = args.length > 1 ? java.lang.Integer.parseInt(args[1]) : 1_000;
        int ringMessages = args.length > 2 ? java.lang.Integer.parseInt(args[2]) : 10_000;
        String ebin = System.getProperty("jerlang.example.ebin", "build/ebin");
        try (Node node = Node.start(NodeOptions.builder()
                .codePath(Path.of(ebin).toAbsolutePath())
                .build())) {
            run(node, "spawn " + spawns + " processes",
                "spawn_count", Integer.of(spawns));
            run(node, "ring " + ringProcesses + " processes / "
                    + ringMessages + " messages",
                "ring", Integer.of(ringProcesses), Integer.of(ringMessages));
        }
    }

    private static void run(Node node, String label, String function, org.jerlang.type.Term... args) {
        long start = System.nanoTime();
        Object result = Bridge.toJava(node.apply("concurrency_demo", function, args));
        double millis = (System.nanoTime() - start) / 1e6;
        System.out.printf("%s: %.1f ms (result %s)%n", label, millis, result);
    }

    private ConcurrencyDemo() {
    }
}
