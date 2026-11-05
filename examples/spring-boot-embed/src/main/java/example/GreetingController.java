package example;

import java.nio.charset.StandardCharsets;
import java.time.Duration;

import org.jerlang.api.Bridge;
import org.jerlang.api.Node;
import org.jerlang.type.Term;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api")
public class GreetingController {

    private static final Duration TIMEOUT = Duration.ofSeconds(2);

    private final Node node;

    public GreetingController(Node node) {
        this.node = node;
    }

    @GetMapping("/greet")
    public Greeting greet(@RequestParam(name = "name", defaultValue = "Spring") String name) {
        Term reply = node.apply("greeter", "greet", TIMEOUT,
            Bridge.toTerm(name.getBytes(StandardCharsets.UTF_8)));

        Object value = Bridge.toJava(reply);
        if (!(value instanceof byte[] bytes)) {
            throw new IllegalStateException("greeter:greet/1 returned " + value);
        }
        return new Greeting(new String(bytes, StandardCharsets.UTF_8));
    }

    public record Greeting(String message) {
    }
}
