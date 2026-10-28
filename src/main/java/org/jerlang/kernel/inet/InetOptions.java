package org.jerlang.kernel.inet;

import java.net.InetAddress;
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
        public final boolean drop;

        Membership(byte[] group, byte[] interfaceAddress, boolean drop) {
            this.group = group;
            this.interfaceAddress = interfaceAddress;
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
    public byte[] ip = null;
    public boolean inet6 = false;
    public int multicastTtl = -1;
    public Boolean multicastLoop = null;
    public byte[] multicastIf = null;
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
        copy.ip = ip == null ? null : ip.clone();
        copy.inet6 = inet6;
        copy.multicastTtl = multicastTtl;
        copy.multicastLoop = multicastLoop;
        copy.multicastIf = multicastIf == null ? null : multicastIf.clone();
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
                multicastIf = InetSupport.addressBytes(value);
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
        return new Membership(
            InetSupport.addressBytes(pair.element(1)),
            InetSupport.addressBytes(pair.element(2)),
            drop);
    }

    public List toList(List requested) {
        List result = List.nil;
        java.util.List<Term> values = new java.util.ArrayList<>();
        while (requested.length() > 0) {
            Term option = requested.head();
            requested = requested.tail();
            if (option instanceof Atom) {
                switch (option.toString()) {
                case "binary":
                    values.add(option);
                    break;
                case "list":
                    if (!binary) {
                        values.add(option);
                    }
                    break;
                case "active":
                    values.add(Tuple.of(option, activeTerm()));
                    break;
                case "packet":
                    values.add(Tuple.of(option, packetTerm()));
                    break;
                case "mode":
                    values.add(Tuple.of(option, Atom.of(binary ? "binary" : "list")));
                    break;
                case "reuseaddr":
                case "nodelay":
                case "keepalive": {
                    boolean enabled = "reuseaddr".equals(option.toString()) ? reuseaddr
                        : "nodelay".equals(option.toString()) ? nodelay : keepalive;
                    values.add(Tuple.of(option, Atom.of(enabled ? "true" : "false")));
                    break;
                }
                default:
                    break;
                }
                continue;
            }
            if (!(option instanceof Tuple)) {
                continue;
            }
            Atom key = ((Tuple) option).element(1).toAtom();
            switch (key.toString()) {
            case "active":
                values.add(Tuple.of(key, activeTerm()));
                break;
            case "packet":
                values.add(Tuple.of(key, packetTerm()));
                break;
            case "mode":
                values.add(Tuple.of(key, Atom.of(binary ? "binary" : "list")));
                break;
            case "reuseaddr":
                values.add(Tuple.of(key, Atom.of(reuseaddr ? "true" : "false")));
                break;
            case "nodelay":
                values.add(Tuple.of(key, Atom.of(nodelay ? "true" : "false")));
                break;
            default:
                break;
            }
        }
        for (int index = values.size() - 1; index >= 0; index--) {
            result = new List(values.get(index), result);
        }
        return result;
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
