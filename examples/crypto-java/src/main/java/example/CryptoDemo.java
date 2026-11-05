package example;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;

import org.jerlang.api.Bridge;
import org.jerlang.api.Node;
import org.jerlang.api.NodeOptions;
import org.jerlang.type.Str;
import org.jerlang.type.Term;

/**
 * Crypto-style operations implemented through the Erlang {@code java} module.
 *
 * <p>This is the NIF-replacement story at application scale: the JDK supplies
 * the primitive, JErlang supplies the Erlang function, and the byte arrays cross
 * the boundary as binaries. It does not make OTP's {@code crypto} module run.
 */
public final class CryptoDemo {

    public static void main(String[] args) {
        String ebin = System.getProperty("jerlang.example.ebin", "build/ebin");
        try (Node node = Node.start(NodeOptions.builder()
                .reflection(true)
                .codePath(Path.of(ebin).toAbsolutePath())
                .build())) {
            System.out.println("sha256(\"abc\") = "
                + text(node, "sha256_hex", Str.of("abc")));
            System.out.println("hmac_sha256(\"Jefe\", message) = "
                + text(node, "hmac_sha256_hex", Str.of("Jefe"),
                    Str.of("what do ya want for nothing?")));
            System.out.println("random(16 bytes) = "
                + text(node, "random_hex", org.jerlang.type.Integer.of(16)));
        }
    }

    private static String text(Node node, String function, Term... arguments) {
        Object value = Bridge.toJava(node.apply("crypto_demo", function, arguments));
        if (value instanceof byte[] bytes) {
            return new String(bytes, StandardCharsets.UTF_8);
        }
        return String.valueOf(value);
    }

    private CryptoDemo() {
    }
}
