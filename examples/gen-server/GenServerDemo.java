import java.nio.file.Path;

import org.jerlang.api.Bridge;
import org.jerlang.api.Node;
import org.jerlang.api.NodeOptions;
import org.jerlang.type.Term;
import org.jerlang.type.Tuple;

/**
 * Java embeds the runtime, starts an Erlang gen_server and calls it.
 *
 * <pre>
 * java -cp target/classes:. -Djerlang.otp.dir=/usr/lib/erlang GenServerDemo
 * </pre>
 *
 * The OTP root is needed because counter is an ordinary gen_server: the
 * server that runs it is OTP's stdlib, not a JErlang copy.
 */
public final class GenServerDemo {

    public static void main(String[] args) {
        String otp = System.getProperty("jerlang.otp.dir");
        if (otp == null) {
            System.err.println("run with -Djerlang.otp.dir=/path/to/erlang");
            System.exit(2);
        }

        try (Node node = Node.start(NodeOptions.builder()
            .otpRoot(Path.of(otp)).build())) {
            Tuple started = node.apply("counter", "start").toTuple();
            Term server = started.element(2);

            node.apply("counter", "increment", server);
            node.apply("counter", "increment", server);
            Object value = Bridge.toJava(node.apply("counter", "value", server));
            System.out.println("counter: " + value);

            node.apply("counter", "stop", server);
        }
    }

}
