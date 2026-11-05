package example;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;

import org.jerlang.api.Bridge;
import org.jerlang.api.Node;
import org.jerlang.api.NodeOptions;
import org.jerlang.type.Integer;

/**
 * A Java service hosting an Erlang node.
 *
 * <p>The Maven build compiles {@code src/main/erl/greeter.erl} with JErlang
 * before this class runs. There is no {@code erlc} step and no Erlang
 * installation involved.
 */
public final class EmbedDemo {

    private EmbedDemo() {
    }

    public static void main(String[] args) {
        String ebin = System.getProperty("jerlang.example.ebin", "target/ebin");

        try (Node node = Node.start(NodeOptions.builder()
            .codePath(Path.of(ebin).toAbsolutePath()).build())) {
            byte[] bytes = (byte[]) Bridge.toJava(node.apply("greeter", "greet",
                Bridge.toTerm("Java".getBytes(StandardCharsets.UTF_8))));
            System.out.println(new String(bytes, StandardCharsets.UTF_8));

            Object product = Bridge.toJava(node.apply("greeter", "product",
                Integer.of(6), Integer.of(7)));
            System.out.println("6 * 7 = " + product);
        }
    }
}
