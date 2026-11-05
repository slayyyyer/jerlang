package example;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;

import org.jerlang.api.Bridge;
import org.jerlang.api.Node;
import org.jerlang.api.NodeOptions;
import org.jerlang.type.Integer;
import org.jerlang.type.Str;
import org.jerlang.type.Term;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CryptoDemoTest {

    private static Node node;

    @BeforeAll
    static void startNode() {
        String ebin = System.getProperty("jerlang.example.ebin", "build/ebin");
        node = Node.start(NodeOptions.builder()
            .reflection(true)
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
    void sha256MatchesTheKnownVector() {
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
            text("sha256_hex", Str.of("abc")));
    }

    @Test
    void hmacSha256MatchesRfc4231CaseTwo() {
        assertEquals("5bdcc146bf60754e6a042426089575c75a003f089d2739839dec58b964ec3843",
            text("hmac_sha256_hex", Str.of("Jefe"),
                Str.of("what do ya want for nothing?")));
    }

    @Test
    void secureRandomReturnsTheRequestedNumberOfBytesAsHex() {
        String value = text("random_hex", Integer.of(32));
        assertEquals(64, value.length());
        assertTrue(value.matches("[0-9a-f]{64}"), value);
    }

    private static String text(String function, Term... arguments) {
        Object value = Bridge.toJava(node.apply("crypto_demo", function, arguments));
        assertTrue(value instanceof byte[], "expected a binary, got " + value);
        return new String((byte[]) value, StandardCharsets.UTF_8);
    }
}
