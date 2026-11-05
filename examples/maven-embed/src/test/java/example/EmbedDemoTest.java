package example;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.jerlang.api.Bridge;
import org.jerlang.api.Node;
import org.jerlang.api.NodeOptions;
import org.jerlang.type.Integer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The consumer-side check: the beam has to be produced by this build, and the
 * node has to call it from Java.
 */
class EmbedDemoTest {

    private static Node node;

    @BeforeAll
    static void startNode() throws IOException {
        Path ebin = Path.of(System.getProperty("jerlang.example.ebin", "target/ebin"))
            .toAbsolutePath();
        Path beam = ebin.resolve("greeter.beam");
        assertTrue(Files.isRegularFile(beam),
            "missing " + beam + "; Maven must run the JErlang compiler first");

        Path source = Path.of("src/main/erl/greeter.erl").toAbsolutePath();
        assertTrue(Files.getLastModifiedTime(beam)
                .compareTo(Files.getLastModifiedTime(source)) >= 0,
            "stale " + beam + "; recompile greeter.erl with JErlang");

        node = Node.start(NodeOptions.builder().codePath(ebin).build());
    }

    @AfterAll
    static void stopNode() {
        if (node != null) {
            node.close();
        }
    }

    @Test
    void greetCrossesTheBoundary() {
        Object value = Bridge.toJava(node.apply("greeter", "greet",
            Bridge.toTerm("Java".getBytes(StandardCharsets.UTF_8))));
        assertTrue(value instanceof byte[], "expected a binary, got " + value);
        assertEquals("hello, Java", new String((byte[]) value, StandardCharsets.UTF_8));
    }

    @Test
    void integerArithmeticStaysErlang() {
        Object value = Bridge.toJava(node.apply("greeter", "product",
            Integer.of(6), Integer.of(7)));
        assertTrue(value instanceof Number, "expected a number, got " + value);
        assertEquals(42, ((Number) value).intValue());
    }
}
