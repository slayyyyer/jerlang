package example;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.concurrent.Executors;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import org.jerlang.api.Bridge;
import org.jerlang.api.Node;
import org.jerlang.api.NodeOptions;
import org.jerlang.erts.Emulator;
import org.jerlang.type.Atom;
import org.jerlang.type.Term;

/**
 * The whole Java side: name this node, start the runtime, listen, and turn each
 * request into one call into Erlang.
 *
 * <p>Every route, status code and byte of the body is decided in
 * {@code src/main/erl/people.erl}. The data is not in this node at all: it is in
 * mnesia on an Erlang/OTP node, reached over distribution.
 *
 * <pre>
 * gradle run
 * gradle run -Pport=9000 -Pnodename=http2 -Pcookie=secret
 * gradle run -Ppeer=mnesia_node@other-host
 * </pre>
 *
 * <p>The peer defaults to {@code mnesia_node} on this node's own host, so no
 * host name has to be typed for a local run.
 */
public final class MnesiaServer {

    private static final String EBIN = "jerlang.example.ebin";
    private static final String PORT = "jerlang.example.port";
    private static final String THREADS = "jerlang.example.threads";
    private static final String NODE_NAME = "jerlang.example.nodename";
    private static final String COOKIE = "jerlang.example.cookie";
    private static final String PEER = "jerlang.example.peer";

    /** The short name the OTP node is expected to use by default. */
    private static final String PEER_NODE = "mnesia_node";

    public static void main(String[] args) throws IOException {
        String ebin = System.getProperty(EBIN, "build/ebin");
        int port = Integer.getInteger(PORT, 8080);
        int threads = Integer.getInteger(THREADS, Math.max(2, Runtime.getRuntime().availableProcessors()));
        String nodeName = System.getProperty(NODE_NAME, "mnesia_http");
        String cookie = System.getProperty(COOKIE, "examplecookie");

        // -sname and -setcookie are the same flags erl takes. Without a name the
        // node is nonode@nohost and can not take part in distribution.
        Emulator.parse(new String[] {"-sname", nodeName, "-setcookie", cookie});
        Node node = Node.start(NodeOptions.builder().schedulers(threads)
            .codePath(Path.of(ebin)).build());
        Atom peer = Atom.of(peerName(node));

        HttpServer http = HttpServer.create(new InetSocketAddress(port), 128);
        http.createContext("/", exchange -> {
            try {
                dispatch(node, peer, exchange);
            } catch (RuntimeException e) {
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

        System.out.println("listening on http://127.0.0.1:" + port + "/ as "
            + node.apply("erlang", "node") + ", peer " + peer);

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            http.stop(0);
            node.close();
        }, "mnesia-service-shutdown"));
    }

    /**
     * The peer to call. A configured value wins; otherwise the host of this
     * node is reused, which is what a local run needs.
     */
    private static String peerName(Node node) {
        String configured = System.getProperty(PEER);
        if (configured != null && !configured.isEmpty()) {
            return configured;
        }
        String local = node.apply("erlang", "node").toString();
        int at = local.indexOf('@');
        return PEER_NODE + (at < 0 ? "" : local.substring(at));
    }

    private static void dispatch(Node node, Atom peer, HttpExchange exchange) throws IOException {
        String path = exchange.getRequestURI().getRawPath();

        // Served by the JVM side, like every example here: scraping has to keep
        // working when the thing that broke is the Erlang code or the peer.
        if (path.equals("/metrics")) {
            send(exchange, 200, "text/plain; charset=utf-8",
                node.metrics().toText().getBytes(StandardCharsets.UTF_8));
            return;
        }

        byte[] body = readAll(exchange.getRequestBody());
        Term reply = node.apply("people", "handle",
            Bridge.toTerm(exchange.getRequestMethod().getBytes(StandardCharsets.UTF_8)),
            Bridge.toTerm(path.getBytes(StandardCharsets.UTF_8)),
            peer,
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
