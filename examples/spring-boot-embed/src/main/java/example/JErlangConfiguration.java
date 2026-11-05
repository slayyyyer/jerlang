package example;

import java.nio.file.Files;
import java.nio.file.Path;

import org.jerlang.api.Node;
import org.jerlang.api.NodeOptions;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The node is a singleton because only one may be open in a JVM. Spring creates
 * it when the context starts and closes it when the context stops, so the
 * service does not need a shutdown hook of its own.
 */
@Configuration(proxyBeanMethods = false)
public class JErlangConfiguration {

    @Bean(destroyMethod = "close")
    public Node jerlangNode(@Value("${jerlang.ebin}") String ebin) {
        Path codePath = Path.of(ebin).toAbsolutePath().normalize();
        if (!Files.isDirectory(codePath)) {
            throw new IllegalStateException("JErlang code path is not a directory: "
                + codePath + ". Run mvn package first.");
        }
        return Node.start(NodeOptions.builder().codePath(codePath).build());
    }
}
