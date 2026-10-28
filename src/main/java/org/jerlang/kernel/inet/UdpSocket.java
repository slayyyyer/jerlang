package org.jerlang.kernel.inet;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketTimeoutException;
import java.util.Arrays;

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
 * One UDP socket opened by gen_udp.
 *
 * Passive mode reads one datagram at a time in recv; active mode runs a
 * reader thread which sends {udp, Socket, Address, Port, Data} messages
 * to the controlling process.
 */
public final class UdpSocket implements InetEndpoint {

    private static final Atom udp = Atom.of("udp");
    private static final Atom udpError = Atom.of("udp_error");
    private static final Atom closedAtom = Atom.of("closed");
    private static final Atom einval = Atom.of("einval");
    private static final Atom enotconn = Atom.of("enotconn");
    private static final Atom ok = Atom.of("ok");
    private static final int MAX_DATAGRAM = 65535;

    private final PortID id;
    private final DatagramSocket socket;
    private final Object receiveLock = new Object();

    private volatile InetOptions options;
    private volatile PID owner;
    private volatile boolean closed = false;
    private volatile Thread reader;

    UdpSocket(PortID id, DatagramSocket socket, InetOptions options, PID owner) {
        this.id = id;
        this.socket = socket;
        this.options = options;
        this.owner = owner;
    }

    @Override
    public PortID id() {
        return id;
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

    public Term recv(int length, int timeoutMillis) {
        if (options.active != InetOptions.Active.FALSE) {
            return InetSupport.error(einval);
        }
        if (length < 0) {
            throw Error.badarg;
        }
        synchronized (receiveLock) {
            try {
                DatagramPacket packet = receivePacket(length, Math.max(timeoutMillis, 0));
                return InetSupport.ok(Tuple.of(
                    InetSupport.address(packet.getAddress()),
                    Integer.of(packet.getPort()),
                    InetSupport.data(Arrays.copyOf(packet.getData(), packet.getLength()),
                        options.binary)));
            } catch (SocketTimeoutException e) {
                return InetSupport.error(Atom.of("timeout"));
            } catch (IOException e) {
                return InetSupport.error(closedAtom);
            }
        }
    }

    public Term send(InetAddress address, int port, byte[] data) {
        if (closed) {
            return InetSupport.error(closedAtom);
        }
        if (port < 0 || port > 65535) {
            throw Error.badarg;
        }
        try {
            socket.send(new DatagramPacket(data, data.length, address, port));
            return ok;
        } catch (IOException e) {
            return InetSupport.error(InetSupport.reason(e));
        }
    }

    /**
     * send/2 is only valid after connect/3 has set the default peer.
     */
    public Term send(byte[] data) {
        if (!socket.isConnected()) {
            return InetSupport.error(enotconn);
        }
        InetSocketAddress remote = (InetSocketAddress) socket.getRemoteSocketAddress();
        if (remote == null || remote.getAddress() == null) {
            return InetSupport.error(enotconn);
        }
        return send(remote.getAddress(), remote.getPort(), data);
    }

    public Term connect(InetAddress address, int port) {
        if (port < 0 || port > 65535) {
            throw Error.badarg;
        }
        try {
            socket.connect(address, port);
            return ok;
        } catch (IllegalArgumentException e) {
            return InetSupport.error(einval);
        }
    }

    @Override
    public Term setopts(List opts) {
        InetOptions updated = options.copy();
        updated.apply(opts);
        options = updated;
        if (updated.active != InetOptions.Active.FALSE) {
            startReader();
        }
        return ok;
    }

    @Override
    public Term getopts(List requested) {
        return InetSupport.ok(options.toList(requested));
    }

    @Override
    public Term sockname() {
        try {
            InetSocketAddress address = (InetSocketAddress) socket.getLocalSocketAddress();
            if (address == null || address.getAddress() == null) {
                return InetSupport.error(closedAtom);
            }
            return InetSupport.ok(Tuple.of(InetSupport.address(address.getAddress()),
                Integer.of(address.getPort())));
        } catch (RuntimeException e) {
            return InetSupport.error(closedAtom);
        }
    }

    @Override
    public Term peername() {
        InetSocketAddress address = (InetSocketAddress) socket.getRemoteSocketAddress();
        if (address == null || address.getAddress() == null) {
            return InetSupport.error(enotconn);
        }
        return InetSupport.ok(Tuple.of(InetSupport.address(address.getAddress()),
            Integer.of(address.getPort())));
    }

    @Override
    public Term port() {
        int port = socket.getLocalPort();
        if (port < 0) {
            return InetSupport.error(closedAtom);
        }
        return InetSupport.ok(Integer.of(port));
    }

    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        UdpSockets.remove(id);
        socket.close();
        Thread running = reader;
        if (running != null) {
            running.interrupt();
        }
    }

    public void start() {
        startReader();
    }

    private void startReader() {
        if (options.active == InetOptions.Active.FALSE || closed) {
            return;
        }
        Thread running = reader;
        if (running != null && running.isAlive()) {
            return;
        }
        Thread thread = new Thread(this::readLoop, "jerlang-udp-" + id);
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
                DatagramPacket packet = receivePacket(0, 200);
                deliver(Tuple.of(
                    udp,
                    id,
                    InetSupport.address(packet.getAddress()),
                    Integer.of(packet.getPort()),
                    InetSupport.data(Arrays.copyOf(packet.getData(), packet.getLength()),
                        options.binary)));
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
            } catch (IOException e) {
                if (!closed) {
                    deliver(Tuple.of(udpError, id, InetSupport.reason(e)));
                }
                close();
                return;
            }
        }
    }

    private DatagramPacket receivePacket(int length, int timeoutMillis) throws IOException {
        int capacity = length > 0 ? length : MAX_DATAGRAM;
        DatagramPacket packet = new DatagramPacket(new byte[capacity], capacity);
        socket.setSoTimeout(timeoutMillis);
        socket.receive(packet);
        return packet;
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

}
