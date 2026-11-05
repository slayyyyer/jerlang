package example;

import java.nio.file.Path;

import org.jerlang.api.Bridge;
import org.jerlang.api.Node;
import org.jerlang.api.NodeOptions;
import org.jerlang.type.Integer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ConcurrencyDemoTest {

    private static Node node;

    @BeforeAll
    static void startNode() {
        String ebin = System.getProperty("jerlang.example.ebin", "build/ebin");
        node = Node.start(NodeOptions.builder()
            .codePath(Path.of(ebin).toAbsolutePath())
            .build());
    }

    @AfterAll
    static void stopNode() {
        if (node != null) {
            node.close();
        }
    }

    @Test
    void spawnsAndJoinsManyProcesses() {
        assertEquals(0, number("spawn_count", Integer.of(5000)));
    }

    @Test
    void aTokenTravelsAroundARing() {
        assertEquals(1000, number("ring", Integer.of(128), Integer.of(1000)));
    }

    private static int number(String function, Integer... arguments) {
        Object value = Bridge.toJava(node.apply("concurrency_demo", function, arguments));
        return ((Number) value).intValue();
    }
}
