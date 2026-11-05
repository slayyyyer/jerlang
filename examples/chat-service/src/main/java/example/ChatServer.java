package example;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import org.jerlang.api.Bridge;
import org.jerlang.api.Node;
import org.jerlang.api.NodeOptions;
import org.jerlang.type.Atom;
import org.jerlang.type.Term;

/**
 * The whole Java side of the chat service: an HTTP listener, one Java function
 * the Erlang side calls back into, and an SSE writer per connection.
 *
 * <p>Routes, frames and JSON are in {@code src/main/erl}. What lives here is the
 * part Erlang cannot do in this runtime: hand a byte stream to a client that is
 * not waiting for a reply.
 *
 * <pre>
 * gradle run                        # port 8080, 64 concurrent streams
 * gradle run -Pport=9000 -Pclients=8
 * </pre>
 *
 * <p>The stream pool is sized by connection, not by core: an SSE response holds
 * its thread for as long as the client stays connected. That is the opposite of
 * the key-value example, where the pool matches the scheduler count.
 */
public final class ChatServer {

    private static final String EBIN = "jerlang.example.ebin";
    private static final String PORT = "jerlang.example.port";
    private static final String CLIENTS = "jerlang.example.clients";
    private static final String SCHEDULERS = "jerlang.example.schedulers";

    private static final byte[] KEEPALIVE = ": keepalive\n\n".getBytes(StandardCharsets.UTF_8);
    private static final byte[] RETRY = "retry: 2000\n\n".getBytes(StandardCharsets.UTF_8);

    /** Connection id to that connection's outbound queue. */
    private static final Map<Long, BlockingQueue<byte[]>> CLIENTS_BY_ID = new ConcurrentHashMap<>();
    private static final AtomicLong NEXT_ID = new AtomicLong();

    private ChatServer() {
    }

    public static void main(String[] args) throws IOException {
        String ebin = System.getProperty(EBIN, "build/ebin");
        int port = Integer.getInteger(PORT, 8080);
        int clients = Integer.getInteger(CLIENTS, 64);
        int schedulers = Integer.getInteger(SCHEDULERS,
            Math.max(2, Runtime.getRuntime().availableProcessors()));

        Node node = Node.start(NodeOptions.builder().schedulers(schedulers)
            .codePath(Path.of(ebin)).build());
        registerSink(node);
        node.apply("chat", "init");

        HttpServer http = HttpServer.create(new InetSocketAddress(port), 128);
        http.createContext("/", exchange -> {
            String path = exchange.getRequestURI().getRawPath();
            try {
                if (path.equals("/metrics")) {
                    send(exchange, 200, "text/plain; charset=utf-8",
                        node.metrics().toText().getBytes(StandardCharsets.UTF_8));
                } else if (path.startsWith("/events/")) {
                    events(node, exchange, path.substring("/events/".length()));
                } else {
                    dispatch(node, exchange, path);
                }
            } catch (RuntimeException e) {
                send(exchange, 500, "application/json",
                    ("{\"error\":\"internal\",\"detail\":\"" + escape(String.valueOf(e.getMessage()))
                        + "\"}\n").getBytes(StandardCharsets.UTF_8));
            } finally {
                exchange.close();
            }
        });
        // Sized by concurrent stream, not by core: see the class comment.
        http.setExecutor(Executors.newFixedThreadPool(clients));
        http.start();

        System.out.println("listening on http://127.0.0.1:" + port + "/ ("
            + clients + " streams, " + schedulers + " schedulers)");

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            http.stop(0);
            node.close();
        }, "chat-service-shutdown"));
    }

    /**
     * The push direction. This runs on the scheduler thread of whichever room
     * process called it, so it must not block: it offers the frame to that
     * connection's queue and says whether the queue took it. The writing is done
     * by the SSE thread, the only place that touches the socket.
     */
    private static void registerSink(Node node) {
        node.registerFunction("chat_sink", "deliver", 2, argv -> {
            BlockingQueue<byte[]> out = CLIENTS_BY_ID.get(number(argv[0]));
            if (out == null) {
                return Atom.of("gone");
            }
            return out.offer((byte[]) Bridge.toJava(argv[1])) ? Atom.of("ok") : Atom.of("full");
        });
    }

    private static void dispatch(Node node, HttpExchange exchange, String path) throws IOException {
        String author = exchange.getRequestHeaders().getFirst("X-Author");
        if (author == null || author.isBlank()) {
            author = "anonymous";
        }
        Term reply = node.apply("chat", "handle",
            Bridge.toTerm(exchange.getRequestMethod().getBytes(StandardCharsets.UTF_8)),
            Bridge.toTerm(path.getBytes(StandardCharsets.UTF_8)),
            Bridge.toTerm(author.getBytes(StandardCharsets.UTF_8)),
            Bridge.toTerm(readAll(exchange.getRequestBody())));

        Object[] parts = (Object[]) Bridge.toJava(reply);
        send(exchange,
            ((Number) parts[0]).intValue(),
            new String((byte[]) parts[1], StandardCharsets.UTF_8),
            (byte[]) parts[2]);
    }

    /**
     * One server-sent events stream: join the room in Erlang, then write every
     * frame its room process hands over, with a comment line every 15 seconds so
     * a dead connection is noticed even when nobody is talking.
     */
    private static void events(Node node, HttpExchange exchange, String room) throws IOException {
        if (room.isEmpty()) {
            send(exchange, 404, "application/json",
                "{\"error\":\"no_such_route\",\"detail\":\"/events/\"}\n".getBytes(StandardCharsets.UTF_8));
            return;
        }

        long id = NEXT_ID.incrementAndGet();
        BlockingQueue<byte[]> out = new ArrayBlockingQueue<>(256);
        CLIENTS_BY_ID.put(id, out);
        try {
            node.apply("chat", "join", Bridge.toTerm(room.getBytes(StandardCharsets.UTF_8)), org.jerlang.type.Integer.of((int) id));

            exchange.getResponseHeaders().set("Content-Type", "text/event-stream; charset=utf-8");
            exchange.getResponseHeaders().set("Cache-Control", "no-cache");
            exchange.sendResponseHeaders(200, 0);

            OutputStream body = exchange.getResponseBody();
            body.write(RETRY);
            body.flush();

            while (true) {
                byte[] frame = out.poll(15, TimeUnit.SECONDS);
                body.write(frame == null ? KEEPALIVE : frame);
                body.flush();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (RuntimeException e) {
            // The node is closed and this stream is over.
        } finally {
            CLIENTS_BY_ID.remove(id);
            try {
                node.apply("chat", "leave", Bridge.toTerm(room.getBytes(StandardCharsets.UTF_8)), org.jerlang.type.Integer.of((int) id));
            } catch (RuntimeException ignored) {
                // Already gone; nothing to clean up on the Erlang side.
            }
        }
    }

    private static long number(Term term) {
        Object value = Bridge.toJava(term);
        return ((Number) value).longValue();
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
