package example;

import java.io.File;

import org.jerlang.api.Bridge;
import org.jerlang.api.Node;
import org.jerlang.api.NodeOptions;
import org.jerlang.type.Integer;
import org.jerlang.type.Term;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Erlang modules under test, called from an ordinary JUnit 5 run.
 *
 * <p>The node is started once for the class and owns the code path through
 * {@link NodeOptions}. Creating one per test would start and stop the
 * schedulers every time, and starting a second one while the first is open is
 * an error.
 */
class PricingTest {

    private static Node node;

    @BeforeAll
    static void startNode() {
        File ebin = new File(System.getProperty("jerlang.example.ebin", "build/ebin"));
        assertTrue(ebin.isDirectory(),
            "missing " + ebin.getAbsolutePath() + " — run gradle compileErlang");

        node = Node.start(NodeOptions.builder().codePath(ebin.toPath()).build());
    }

    @AfterAll
    static void stopNode() {
        if (node != null) {
            node.close();
        }
    }

    @Test
    void quoteMultipliesPriceByQuantity() {
        assertEquals(1000, number("pricing", "quote", Integer.of(250), Integer.of(4)));
    }

    @Test
    void taxIsAppliedWithIntegerRounding() {
        assertEquals(1190, number("pricing", "with_tax", Integer.of(1000), Integer.of(19)));
    }

    @Test
    void totalSumsAList() {
        assertEquals(750, number("pricing", "sample_total"));
    }

    @Test
    void aProcessKeepsItsState() {
        assertEquals(2, number("worker", "demo"));
    }

    @Test
    void aClauseThatDoesNotMatchSurfacesAsAnException() {
        RuntimeException failure = assertThrows(RuntimeException.class,
            () -> node.apply("pricing", "quote", Integer.of(250), Integer.of(-1)));
        assertTrue(failure.getClass().getName().startsWith("org.jerlang"),
            "expected the runtime's own exception, got " + failure.getClass().getName());
    }

    private static int number(String module, String function, Term... arguments) {
        Object value = Bridge.toJava(node.apply(module, function, arguments));
        assertTrue(value instanceof Number, "expected a number, got " + value);
        return ((Number) value).intValue();
    }
}
