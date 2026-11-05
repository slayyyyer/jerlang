package example;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicLong;

/**
 * A Java service that is deliberately outside the Erlang supervision tree.
 *
 * <p>It listens on a loopback socket and answers every request with {@code 200
 * OK}. The example keeps traffic flowing through it while the supervised
 * Erlang worker is killed, so the output distinguishes "the worker crashed"
 * from "the JVM service stopped serving".
 */
final class JavaService implements AutoCloseable {

    private final ServerSocket server;
    private final Thread thread;
    private final AtomicLong served = new AtomicLong();

    JavaService() throws IOException {
        server = new ServerSocket(0, 32, InetAddress.getLoopbackAddress());
        thread = Thread.ofVirtual().name("java-service").start(this::serve);
    }

    int port() {
        return server.getLocalPort();
    }

    long served() {
        return served.get();
    }

    /**
     * Make one request as a client would. The returned status line is the
     * evidence printed by the example.
     */
    String request() throws IOException {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(InetAddress.getLoopbackAddress(),
                server.getLocalPort()), 1000);
            socket.setSoTimeout(1000);
            OutputStream out = socket.getOutputStream();
            out.write(("GET /health HTTP/1.1\r\n"
                + "Host: localhost\r\n"
                + "Connection: close\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
            out.flush();
            BufferedReader in = new BufferedReader(new InputStreamReader(
                socket.getInputStream(), StandardCharsets.US_ASCII));
            return in.readLine();
        }
    }

    private void serve() {
        while (!server.isClosed()) {
            try (Socket socket = server.accept()) {
                BufferedReader in = new BufferedReader(new InputStreamReader(
                    socket.getInputStream(), StandardCharsets.US_ASCII));
                String line;
                while ((line = in.readLine()) != null && !line.isEmpty()) {
                    // Drain the request headers; the service has no routes.
                }
                served.incrementAndGet();
                byte[] body = "ok\n".getBytes(StandardCharsets.US_ASCII);
                String head = "HTTP/1.1 200 OK\r\n"
                    + "Content-Type: text/plain\r\n"
                    + "Content-Length: " + body.length + "\r\n"
                    + "Connection: close\r\n\r\n";
                OutputStream out = socket.getOutputStream();
                out.write(head.getBytes(StandardCharsets.US_ASCII));
                out.write(body);
                out.flush();
            } catch (IOException failure) {
                if (server.isClosed()) {
                    return;
                }
                // A malformed client must not take the service down.
            }
        }
    }

    @Override
    public void close() throws IOException {
        server.close();
        thread.interrupt();
        try {
            thread.join(1000);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }

}
