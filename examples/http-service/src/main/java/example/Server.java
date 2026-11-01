package example;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.Executors;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import org.jerlang.CodePaths;
import org.jerlang.ModuleRegistry;
import org.jerlang.api.Bridge;
import org.jerlang.api.Node;
import org.jerlang.api.NodeOptions;
import org.jerlang.type.Term;

/**
 * The whole Java side of the service: an HTTP listener that turns a request
 * into one call into Erlang and a reply into a response.
 *
 * <p>Every route, status code and byte of the body is decided in
 * {@code src/main/erl/api.erl}. Nothing here needs to change to add one.
 *
 * <pre>
 * gradle run                  # port 8080
 * gradle run -Pport=9000      # another port
 * </pre>
 *
 * <p>The thread pool and the scheduler count are the same number on purpose: one
 * HTTP thread is waiting for one Erlang call at a time, so more HTTP threads
 * than schedulers would only queue work that is already runnable.
 */
public final class Server {

    private static final String EBIN = "jerlang.example.ebin";
    private static final String PORT = "jerlang.example.port";
    private static final String THREADS = "jerlang.example.threads";

    public static void main(String[] args) throws IOException {
        String ebin = System.getProperty(EBIN, "build/ebin");
        int port = Integer.getInteger(PORT, 8080);
        int threads = Integer.getInteger(THREADS, Math.max(2, Runtime.getRuntime().availableProcessors()));

        CodePaths.reset();
        ModuleRegistry.reset();
        CodePaths.addPatha(CodePaths.normalizePath(ebin));

        Node node = Node.start(NodeOptions.builder().schedulers(threads).build());
        node.apply("api", "init");

        HttpServer http = HttpServer.create(new InetSocketAddress(port), 128);
        http.createContext("/", exchange -> {
            try {
                dispatch(node, exchange);
            } catch (RuntimeException e) {
                // An Erlang crash surfaces here as an exception from apply. The
                // service stays up, which is the point of putting the state in
                // processes: one failed call does not take the listener with it.
                byte[] body = ("{\"error\":\"internal\",\"detail\":\""
                    + escape(String.valueOf(e.getMessage())) + "\"}\n")
                    .getBytes(StandardCharsets.UTF_8);
                send(exchange, 500, "application/json", body);
            } finally {
                exchange.close();
            }
        });
        http.setExecutor(Executors.newFixedThreadPool(threads));
        http.start();

        System.out.println("listening on http://127.0.0.1:" + port + "/ (" + threads + " threads)");

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            // Stop accepting first, so nothing new arrives while the node drains.
            http.stop(0);
            node.close();
        }, "http-service-shutdown"));
    }

    private static void dispatch(Node node, HttpExchange exchange) throws IOException {
        String path = exchange.getRequestURI().getRawPath();

        // Served from the runtime's own counters, not from Erlang: scraping
        // must keep working when the service's own code is the thing that broke.
        if (path.equals("/metrics")) {
            send(exchange, 200, "text/plain; charset=utf-8",
                node.metrics().toText().getBytes(StandardCharsets.UTF_8));
            return;
        }

        byte[] body = readAll(exchange.getRequestBody());
        Term reply = node.apply("api", "handle",
            Bridge.toTerm(exchange.getRequestMethod().getBytes(StandardCharsets.UTF_8)),
            Bridge.toTerm(path.getBytes(StandardCharsets.UTF_8)),
            Bridge.toTerm(body));

        Object[] parts = (Object[]) Bridge.toJava(reply);
        send(exchange,
            ((Number) parts[0]).intValue(),
            new String((byte[]) parts[1], StandardCharsets.UTF_8),
            (byte[]) parts[2]);
    }

    private static void send(HttpExchange exchange, int status, String contentType, byte[] body)
        throws IOException {
        exchange.getResponseHeaders().set("Content-Type", contentType);
        if (body.length == 0 || status == 204 || status == 304) {
            exchange.sendResponseHeaders(status, -1);
            return;
        }
        exchange.sendResponseHeaders(status, body.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(body);
        }
    }

    private static byte[] readAll(InputStream in) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        byte[] chunk = new byte[8192];
        int read;
        while ((read = in.read(chunk)) >= 0) {
            buffer.write(chunk, 0, read);
        }
        return buffer.toByteArray();
    }

    private static String escape(String text) {
        return text == null ? "" : text.replace("\\", "\\\\").replace("\"", "\\\"");
    }

}
