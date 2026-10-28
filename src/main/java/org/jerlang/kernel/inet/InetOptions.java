package org.jerlang.kernel.inet;

import java.io.IOException;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.UnknownHostException;

import org.jerlang.erts.erlang.Error;
import org.jerlang.type.Atom;
import org.jerlang.type.Binary;
import org.jerlang.type.Integer;
import org.jerlang.type.List;
import org.jerlang.type.Str;
import org.jerlang.type.Term;
import org.jerlang.type.Tuple;

/**
 * The subset of inet options that matters for TCP and UDP sockets.
 *
 * See:
 * http://www.erlang.org/doc/man/inet.html#setopts-2
 */
public final class InetOptions {

    public enum Active {
        FALSE, TRUE, ONCE, N
    }

    /**
     * One add_membership or drop_membership option.
     */
    public static final class Membership {

        public final byte[] group;
        public final byte[] interfaceAddress;
        public final int interfaceIndex;
        public final boolean drop;

        Membership(byte[] group, byte[] interfaceAddress, int interfaceIndex, boolean drop) {
            this.group = group;
            this.interfaceAddress = interfaceAddress;
            this.interfaceIndex = interfaceIndex;
            this.drop = drop;
        }

    }

    public boolean binary = false;
    public int packet = 0; // -1 means line
    public volatile Active active = Active.TRUE;
    public volatile int activeCount = -1;
    public boolean reuseaddr = false;
    public boolean nodelay = false;
    public boolean keepalive = false;
    public int backlog = 5;
    public boolean linger = false;
    public int lingerSeconds = 0;
    public int sndbuf = -1;
    public int recbuf = -1;
    public int buffer = -1;
    public int tos = 0;
    public boolean exitOnClose = true;
    public boolean delaySend = false;
    public byte[] ip = null;
    public boolean inet6 = false;
    public int multicastTtl = -1;
    public Boolean multicastLoop = null;
    public byte[] multicastIf = null;
    public int multicastIfIndex = -1;
    public final java.util.List<Membership> memberships = new java.util.ArrayList<>();

    public static InetOptions parse(List options) {
        InetOptions parsed = new InetOptions();
        parsed.apply(options);
        return parsed;
    }

    public InetOptions copy() {
        InetOptions copy = new InetOptions();
        copy.binary = binary;
        copy.packet = packet;
        copy.active = active;
        copy.activeCount = activeCount;
        copy.reuseaddr = reuseaddr;
        copy.nodelay = nodelay;
        copy.keepalive = keepalive;
        copy.backlog = backlog;
        copy.linger = linger;
        copy.lingerSeconds = lingerSeconds;
        copy.sndbuf = sndbuf;
        copy.recbuf = recbuf;
        copy.buffer = buffer;
        copy.tos = tos;
        copy.exitOnClose = exitOnClose;
        copy.delaySend = delaySend;
        copy.ip = ip == null ? null : ip.clone();
        copy.inet6 = inet6;
        copy.multicastTtl = multicastTtl;
        copy.multicastLoop = multicastLoop;
        copy.multicastIf = multicastIf == null ? null : multicastIf.clone();
        copy.multicastIfIndex = multicastIfIndex;
        return copy;
    }

    public void apply(List options) {
        while (options.length() > 0) {
            Term option = options.head();
            options = options.tail();
            if (option instanceof Atom) {
                String name = option.toString();
                switch (name) {
                case "binary":
                    binary = true;
                    break;
                case "list":
                    binary = false;
                    break;
                case "inet":
                    inet6 = false;
                    break;
                case "inet6":
                    inet6 = true;
                    break;
                default:
                    // Unknown atoms are ignored, like OTP ignores some
                    // socket options it does not know about.
                    break;
                }
                continue;
            }
            if (!(option instanceof Tuple) || ((Tuple) option).arity() != 2) {
                throw Error.badarg;
            }
            Tuple pair = (Tuple) option;
            Atom key = pair.element(1).toAtom();
            Term value = pair.element(2);
            switch (key.toString()) {
            case "packet":
                packet = packet(value);
                break;
            case "active":
                active(value);
                break;
            case "mode":
                binary = "binary".equals(value.toString());
                break;
            case "reuseaddr":
                reuseaddr = truth(value);
                break;
            case "nodelay":
                nodelay = truth(value);
                break;
            case "keepalive":
                keepalive = truth(value);
                break;
            case "backlog":
                backlog = value.toInteger().toInt();
                break;
            case "linger":
                linger(value);
                break;
            case "sndbuf":
                sndbuf = value.toInteger().toInt();
                break;
            case "recbuf":
                recbuf = value.toInteger().toInt();
                break;
            case "buffer":
                buffer = value.toInteger().toInt();
                break;
            case "tos":
                tos = value.toInteger().toInt();
                break;
            case "exit_on_close":
                exitOnClose = truth(value);
                break;
            case "delay_send":
                delaySend = truth(value);
                break;
            case "ip":
                ip = InetSupport.addressBytes(value);
                inet6 = ip.length == 16;
                break;
            case "family":
                inet6 = "inet6".equals(value.toString());
                break;
            case "multicast_ttl":
                multicastTtl = value.toInteger().toInt();
                break;
            case "multicast_loop":
                multicastLoop = truth(value);
                break;
            case "multicast_if":
                if (value instanceof Integer) {
                    multicastIfIndex = value.toInteger().toInt();
                    multicastIf = null;
                } else {
                    multicastIf = InetSupport.addressBytes(value);
                    multicastIfIndex = -1;
                }
                break;
            case "add_membership":
                memberships.add(membership(value, false));
                break;
            case "drop_membership":
                memberships.add(membership(value, true));
                break;
            default:
                // exit_on_close, buffer, recbuf, sndbuf, send_timeout and
                // friends do not change the Java socket semantics here.
                break;
            }
        }
    }

    private void active(Term value) {
        if (value instanceof Integer) {
            active = Active.N;
            activeCount = value.toInteger().toInt();
            return;
        }
        String name = value.toString();
        switch (name) {
        case "false":
            active = Active.FALSE;
            break;
        case "true":
            active = Active.TRUE;
            break;
        case "once":
            active = Active.ONCE;
            break;
        default:
            throw Error.badarg;
        }
    }

    private static int packet(Term value) {
        String name = value.toString();
        switch (name) {
        case "raw":
            return 0;
        case "line":
            return -1;
        case "1":
            return 1;
        case "2":
            return 2;
        case "4":
            return 4;
        default:
            return value.toInteger().toInt();
        }
    }

    private static boolean truth(Term value) {
        return "true".equals(value.toString());
    }

    private static Membership membership(Term value, boolean drop) {
        Tuple pair = value.toTuple();
        if (pair.arity() != 2) {
            throw Error.badarg;
        }
        // IPv4 names an interface by address, IPv6 by interface index.
        Term intf = pair.element(2);
        if (intf instanceof Integer) {
            return new Membership(InetSupport.addressBytes(pair.element(1)), null,
                intf.toInteger().toInt(), drop);
        }
        return new Membership(
            InetSupport.addressBytes(pair.element(1)),
            InetSupport.addressBytes(intf),
            -1,
            drop);
    }

    /**
     * Set {linger, {Boolean, Seconds}}. A plain boolean disables lingering.
     */
    private void linger(Term value) {
        if (value instanceof Tuple) {
            Tuple pair = (Tuple) value;
            if (pair.arity() != 2) {
                throw Error.badarg;
            }
            linger = truth(pair.element(1));
            lingerSeconds = linger ? pair.element(2).toInteger().toInt() : 0;
        } else {
            linger = truth(value);
            lingerSeconds = 0;
        }
    }

    public List toList(List requested) {
        List result = List.nil;
        java.util.List<Term> values = new java.util.ArrayList<>();
        while (requested.length() > 0) {
            Term option = requested.head();
            requested = requested.tail();
            if (option instanceof Atom) {
                String name = option.toString();
                if ("binary".equals(name)) {
                    values.add(option);
                    continue;
                }
                if ("list".equals(name)) {
                    if (!binary) {
                        values.add(option);
                    }
                    continue;
                }
                Term value = optionValue(name);
                if (value != null) {
                    values.add(Tuple.of(option, value));
                }
                continue;
            }
            if (!(option instanceof Tuple)) {
                continue;
            }
            Atom key = ((Tuple) option).element(1).toAtom();
            Term value = optionValue(key.toString());
            if (value != null) {
                values.add(Tuple.of(key, value));
            }
        }
        for (int index = values.size() - 1; index >= 0; index--) {
            result = new List(values.get(index), result);
        }
        return result;
    }

    private Term optionValue(String name) {
        switch (name) {
        case "active":
            return activeTerm();
        case "packet":
            return packetTerm();
        case "mode":
            return Atom.of(binary ? "binary" : "list");
        case "reuseaddr":
            return truth(reuseaddr);
        case "nodelay":
            return truth(nodelay);
        case "keepalive":
            return truth(keepalive);
        case "exit_on_close":
            return truth(exitOnClose);
        case "delay_send":
            return truth(delaySend);
        case "linger":
            return linger
                ? Tuple.of(Atom.of("true"), Integer.of(lingerSeconds))
                : Atom.of("false");
        case "sndbuf":
            return sndbuf < 0 ? null : Integer.of(sndbuf);
        case "recbuf":
            return recbuf < 0 ? null : Integer.of(recbuf);
        case "buffer":
            return buffer < 0 ? null : Integer.of(buffer);
        case "tos":
            return Integer.of(tos);
        case "backlog":
            return Integer.of(backlog);
        case "ip":
            return ip == null ? null : address(ip);
        default:
            return null;
        }
    }

    private static Term truth(boolean value) {
        return Atom.of(value ? "true" : "false");
    }

    private static Term address(byte[] bytes) {
        try {
            return InetSupport.address(InetAddress.getByAddress(bytes));
        } catch (UnknownHostException e) {
            return null;
        }
    }

    /**
     * Apply the options that can change on an open TCP socket. Buffer sizes
     * are read back so getopts reports what the OS actually uses.
     */
    public void applyToSocket(Socket socket) throws IOException {
        socket.setTcpNoDelay(nodelay);
        socket.setKeepAlive(keepalive);
        socket.setSoLinger(linger, linger ? lingerSeconds : -1);
        if (sndbuf >= 0) {
            socket.setSendBufferSize(sndbuf);
            sndbuf = socket.getSendBufferSize();
        }
        if (recbuf >= 0) {
            socket.setReceiveBufferSize(recbuf);
            recbuf = socket.getReceiveBufferSize();
        }
        if (tos > 0) {
            socket.setTrafficClass(tos);
        }
    }

    /**
     * Apply the listener options that have to be set before bind. Accepted
     * sockets inherit them.
     */
    public void applyToListener(ServerSocket server) throws IOException {
        server.setReuseAddress(reuseaddr);
        applyReceiveBuffer(server);
    }

    /**
     * Change the receive buffer of an already bound listener. Connections
     * accepted afterwards inherit the new size.
     */
    public void applyReceiveBuffer(ServerSocket server) throws IOException {
        if (recbuf >= 0) {
            server.setReceiveBufferSize(recbuf);
            recbuf = server.getReceiveBufferSize();
        }
    }

    public void applyToSocket(DatagramSocket socket) throws IOException {
        if (sndbuf >= 0) {
            socket.setSendBufferSize(sndbuf);
            sndbuf = socket.getSendBufferSize();
        }
        if (recbuf >= 0) {
            socket.setReceiveBufferSize(recbuf);
            recbuf = socket.getReceiveBufferSize();
        }
    }

    /**
     * Record the buffer sizes a socket was created with, so getopts can
     * answer even when the option was never set explicitly.
     */
    public void captureSizes(Socket socket) {
        try {
            if (sndbuf < 0) {
                sndbuf = socket.getSendBufferSize();
            }
            if (recbuf < 0) {
                recbuf = socket.getReceiveBufferSize();
            }
        } catch (IOException ignored) {
            // Option reporting is best effort.
        }
    }

    public void captureSizes(ServerSocket server) {
        if (recbuf >= 0) {
            return;
        }
        try {
            recbuf = server.getReceiveBufferSize();
        } catch (IOException ignored) {
            // Option reporting is best effort.
        }
    }

    public void captureSizes(DatagramSocket socket) {
        try {
            if (sndbuf < 0) {
                sndbuf = socket.getSendBufferSize();
            }
            if (recbuf < 0) {
                recbuf = socket.getReceiveBufferSize();
            }
        } catch (IOException ignored) {
            // Option reporting is best effort.
        }
    }

    public Term activeTerm() {
        switch (active) {
        case TRUE:
            return Atom.of("true");
        case ONCE:
            return Atom.of("once");
        case N:
            return Integer.of(activeCount);
        default:
            return Atom.of("false");
        }
    }

    public Term packetTerm() {
        return packet < 0 ? Atom.of("line") : Integer.of(packet);
    }

    /**
     * Resolve an Erlang address (a tuple, an atom or a string) to an
     * InetAddress.
     */
    public static InetAddress toAddress(Term term) {
        if (term instanceof Tuple) {
            return InetSupport.address(term);
        }
        try {
            return InetAddress.getByName(InetSupport.text(term));
        } catch (UnknownHostException e) {
            throw Error.badarg;
        }
    }

    public static byte[] bytesOf(Term term) {
        if (term instanceof Binary) {
            return ((Binary) term).toByteArray();
        }
        if (term instanceof Str) {
            return ((Str) term).toByteArray();
        }
        return null;
    }
}
