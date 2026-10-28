package org.jerlang.kernel.inet;

import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;

import org.jerlang.Process;
import org.jerlang.ProcessRegistry;
import org.jerlang.erts.erlang.Error;
import org.jerlang.type.Atom;
import org.jerlang.type.Integer;
import org.jerlang.type.List;
import org.jerlang.type.PID;
import org.jerlang.type.PortID;
import org.jerlang.type.Term;
import org.jerlang.type.Tuple;

/**
 * One listening or connected TCP socket.
 *
 * The Erlang side sees a port identifier. Passive mode reads on demand in
 * recv; active mode runs a reader thread which sends {tcp, Socket, Data}
 * messages to the controlling process.
 */
public final class InetSocket implements InetEndpoint {

    private static final Atom tcp = Atom.of("tcp");
    private static final Atom tcpClosed = Atom.of("tcp_closed");
    private static final Atom tcpError = Atom.of("tcp_error");
    private static final Atom closedAtom = Atom.of("closed");
    private static final Atom einval = Atom.of("einval");
    private static final Atom ok = Atom.of("ok");
    private static final int MAX_PACKET = 64 * 1024 * 1024;

    private final PortID id;
    private final ServerSocket server;
    private final Socket socket;
    private final InputStream in;
    private final OutputStream out;
    private final Object readLock = new Object();
    private final Object writeLock = new Object();

    private volatile InetOptions options;
    private volatile PID owner;
    private volatile boolean closed = false;
    private volatile Thread reader;

    InetSocket(PortID id, ServerSocket server, Socket socket, InetOptions options, PID owner)
        throws IOException {
        this.id = id;
        this.server = server;
        this.socket = socket;
        this.options = options;
        this.owner = owner;
        if (socket != null) {
            in = socket.getInputStream();
            out = socket.getOutputStream();
        } else {
            in = null;
            out = null;
        }
    }

    public PortID id() {
        return id;
    }

    public ServerSocket listener() {
        return server;
    }

    public Socket socket() {
        return socket;
    }

    public InetOptions options() {
        return options;
    }

    public PID owner() {
        return owner;
    }

    public void controllingProcess(PID pid) {
        owner = pid;
    }

    public boolean isClosed() {
        return closed;
    }

    /**
     * Accept one connection. The accepted socket inherits the listener's
     * options and starts an active reader when requested.
     */
    public InetSocket accept(int timeoutMillis, PID owner) throws IOException {
        server.setSoTimeout(Math.max(timeoutMillis, 0));
        Socket accepted;
        try {
            accepted = server.accept();
        } finally {
            server.setSoTimeout(0);
        }
        accepted.setTcpNoDelay(options.nodelay);
        accepted.setKeepAlive(options.keepalive);
        InetSocket result = InetSockets.register(accepted, null,
            options.copy(), owner == null ? this.owner : owner);
        result.startReader();
        return result;
    }

    public Term recv(int length, int timeoutMillis) {
        if (options.active != InetOptions.Active.FALSE) {
            return InetSupport.error(einval);
        }
        synchronized (readLock) {
            try {
                byte[] data = readFrame(length, Math.max(timeoutMillis, 0), false);
                return InetSupport.ok(InetSupport.data(data, options.binary));
            } catch (SocketTimeoutException e) {
                return InetSupport.error(Atom.of("timeout"));
            } catch (EOFException e) {
                return InetSupport.error(closedAtom);
            } catch (IOException e) {
                return InetSupport.error(closedAtom);
            }
        }
    }

    public Term send(byte[] data) {
        if (socket == null) {
            throw Error.badarg;
        }
        synchronized (writeLock) {
            if (closed) {
                return InetSupport.error(closedAtom);
            }
            try {
                out.write(data);
                out.flush();
                return ok;
            } catch (IOException e) {
                return InetSupport.error(closedAtom);
            }
        }
    }

    public Term setopts(List opts) {
        InetOptions updated = options.copy();
        updated.apply(opts);
        options = updated;
        if (updated.active != InetOptions.Active.FALSE) {
            startReader();
        }
        return ok;
    }

    public Term getopts(List requested) {
        return InetSupport.ok(options.toList(requested));
    }

    public Term sockname() {
        try {
            InetSocketAddress address;
            if (server != null) {
                address = new InetSocketAddress(server.getInetAddress(), server.getLocalPort());
            } else {
                address = (InetSocketAddress) socket.getLocalSocketAddress();
            }
            return InetSupport.ok(Tuple.of(InetSupport.address(address.getAddress()),
                Integer.of(address.getPort())));
        } catch (RuntimeException e) {
            return InetSupport.error(closedAtom);
        }
    }

    public Term peername() {
        if (socket == null) {
            throw Error.badarg;
        }
        try {
            InetSocketAddress address = (InetSocketAddress) socket.getRemoteSocketAddress();
            if (address == null) {
                return InetSupport.error(closedAtom);
            }
            return InetSupport.ok(Tuple.of(InetSupport.address(address.getAddress()),
                Integer.of(address.getPort())));
        } catch (RuntimeException e) {
            return InetSupport.error(closedAtom);
        }
    }

    public Term port() {
        if (server != null) {
            return InetSupport.ok(Integer.of(server.getLocalPort()));
        }
        if (socket != null) {
            return InetSupport.ok(Integer.of(socket.getLocalPort()));
        }
        throw Error.badarg;
    }

    public Term shutdown(Atom how) {
        if (socket == null) {
            throw Error.badarg;
        }
        try {
            switch (how.toString()) {
            case "read":
                socket.shutdownInput();
                break;
            case "write":
                socket.shutdownOutput();
                break;
            case "read_write":
                socket.shutdownInput();
                socket.shutdownOutput();
                break;
            default:
                throw Error.badarg;
            }
            return ok;
        } catch (IOException e) {
            return InetSupport.error(closedAtom);
        }
    }

    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        InetSockets.remove(id);
        try {
            if (socket != null) {
                socket.close();
            }
            if (server != null) {
                server.close();
            }
        } catch (IOException ignored) {
            // Closing is best effort.
        }
        Thread running = reader;
        if (running != null) {
            running.interrupt();
        }
    }

    public void start() {
        startReader();
    }

    private void startReader() {
        if (socket == null || options.active == InetOptions.Active.FALSE || closed) {
            return;
        }
        Thread running = reader;
        if (running != null && running.isAlive()) {
            return;
        }
        Thread thread = new Thread(this::readLoop, "jerlang-tcp-" + id);
        thread.setDaemon(true);
        reader = thread;
        thread.start();
    }

    private void readLoop() {
        while (!closed) {
            InetOptions.Active mode = options.active;
            if (mode == InetOptions.Active.FALSE) {
                return;
            }
            try {
                byte[] data = readFrame(0, 200, true);
                if (data == null) {
                    continue;
                }
                deliver(Tuple.of(tcp, id, InetSupport.data(data, options.binary)));
                if (mode == InetOptions.Active.ONCE) {
                    options.active = InetOptions.Active.FALSE;
                    return;
                }
                if (mode == InetOptions.Active.N) {
                    options.activeCount--;
                    if (options.activeCount <= 0) {
                        options.active = InetOptions.Active.FALSE;
                        return;
                    }
                }
            } catch (SocketTimeoutException e) {
                // Poll again so that setopts({active,false}) can stop us.
            } catch (EOFException e) {
                if (!closed) {
                    deliver(Tuple.of(tcpClosed, id));
                }
                close();
                return;
            } catch (IOException e) {
                if (!closed) {
                    deliver(Tuple.of(tcpError, id, InetSupport.reason(e)));
                }
                close();
                return;
            }
        }
    }

    private void deliver(Term message) {
        PID pid = owner;
        if (pid == null) {
            return;
        }
        Process process = (Process) ProcessRegistry.resolve(pid);
        if (process != null) {
            process.send(message);
        }
    }

    /**
     * Read one packet. A timeout of 0 means wait forever; with polling
     * true a socket timeout returns null so the caller can check its
     * active mode.
     */
    private byte[] readFrame(int length, int timeoutMillis, boolean polling) throws IOException {
        socket.setSoTimeout(timeoutMillis);
        int packet = options.packet;
        if (packet == -1) {
            return readLine();
        }
        if (packet > 0) {
            byte[] header = readExactly(packet);
            int size = 0;
            for (byte b : header) {
                size = (size << 8) | (b & 0xFF);
            }
            if (size < 0 || size > MAX_PACKET) {
                throw new IOException("Invalid packet length " + size);
            }
            return readExactly(size);
        }
        if (length > 0) {
            return readExactly(length);
        }
        int first;
        try {
            first = in.read();
        } catch (SocketTimeoutException e) {
            if (polling) {
                return null;
            }
            throw e;
        }
        if (first < 0) {
            throw new EOFException();
        }
        ByteArrayOutputStream data = new ByteArrayOutputStream();
        data.write(first);
        int available = in.available();
        while (available > 0) {
            byte[] buffer = new byte[available];
            int read = in.read(buffer);
            if (read < 0) {
                break;
            }
            data.write(buffer, 0, read);
            available = in.available();
        }
        return data.toByteArray();
    }

    private byte[] readLine() throws IOException {
        ByteArrayOutputStream data = new ByteArrayOutputStream();
        while (true) {
            int b = in.read();
            if (b < 0) {
                if (data.size() == 0) {
                    throw new EOFException();
                }
                return data.toByteArray();
            }
            data.write(b);
            if (b == '\n') {
                return data.toByteArray();
            }
        }
    }

    private byte[] readExactly(int length) throws IOException {
        byte[] data = new byte[length];
        int offset = 0;
        while (offset < length) {
            int read = in.read(data, offset, length - offset);
            if (read < 0) {
                throw new EOFException();
            }
            offset += read;
        }
        return data;
    }
}
