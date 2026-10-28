package org.jerlang.kernel.inet;

import java.io.ByteArrayOutputStream;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.UnknownHostException;

import org.jerlang.ProcessOrPort;
import org.jerlang.ProcessRegistry;
import org.jerlang.erts.erlang.Error;
import org.jerlang.type.Atom;
import org.jerlang.type.Binary;
import org.jerlang.type.BitString;
import org.jerlang.type.Integer;
import org.jerlang.type.List;
import org.jerlang.type.PID;
import org.jerlang.type.Str;
import org.jerlang.type.Term;
import org.jerlang.type.Tuple;

/**
 * Conversions between Erlang terms and the java.net types used by the
 * native gen_tcp and inet modules.
 */
public final class InetSupport {

    private static final Atom ok = Atom.of("ok");
    private static final Atom error = Atom.of("error");

    private InetSupport() {
    }

    public static Tuple ok(Term value) {
        return Tuple.of(ok, value);
    }

    public static Tuple error(Atom reason) {
        return Tuple.of(error, reason);
    }

    public static InetSocket socket(Term term) {
        InetSocket socket = InetSockets.get(term);
        if (socket == null) {
            throw Error.badarg;
        }
        return socket;
    }

    public static InetSocket listener(Term term) {
        InetSocket socket = socket(term);
        if (socket.listener() == null) {
            throw Error.badarg;
        }
        return socket;
    }

    public static InetSocket connected(Term term) {
        InetSocket socket = socket(term);
        if (socket.socket() == null) {
            throw Error.badarg;
        }
        return socket;
    }

    public static int integer(Term term) {
        return term.toInteger().toInt();
    }

    public static PID selfPid() {
        ProcessOrPort self = ProcessRegistry.self();
        return self instanceof org.jerlang.Process ? self.pid() : null;
    }

    /**
     * A timeout is either milliseconds or the atom infinity (zero here).
     */
    public static int timeout(Term term) {
        if (term instanceof Atom && "infinity".equals(term.toString())) {
            return 0;
        }
        return term.toInteger().toInt();
    }

    public static String text(Term term) {
        if (term instanceof Atom) {
            return term.toString();
        }
        if (term instanceof Str) {
            return ((Str) term).string();
        }
        if (term instanceof BitString) {
            return new String(((BitString) term).toByteArray());
        }
        if (term instanceof List) {
            StringBuilder text = new StringBuilder();
            List list = (List) term;
            while (list.length() > 0) {
                text.append((char) list.head().toInteger().toInt());
                list = list.tail();
            }
            return text.toString();
        }
        throw Error.badarg;
    }

    /**
     * Convert Erlang iodata (binaries, strings and nested lists of bytes)
     * to a byte array.
     */
    public static byte[] data(Term term) {
        if (term instanceof Binary) {
            return ((Binary) term).toByteArray();
        }
        if (term instanceof BitString) {
            return ((BitString) term).toByteArray();
        }
        if (term instanceof Integer) {
            return new byte[] { (byte) term.toInteger().toInt() };
        }
        if (term instanceof Str) {
            return ((Str) term).toByteArray();
        }
        if (term instanceof List) {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            List list = (List) term;
            while (list.length() > 0) {
                byte[] element = data(list.head());
                bytes.write(element, 0, element.length);
                list = list.tail();
            }
            return bytes.toByteArray();
        }
        throw Error.badarg;
    }

    public static Term data(byte[] bytes, boolean binary) {
        if (binary) {
            return Binary.of(bytes);
        }
        List list = List.nil;
        for (int index = bytes.length - 1; index >= 0; index--) {
            list = new List(Integer.of(bytes[index] & 0xFF), list);
        }
        return list;
    }

    public static Term ntoa(Term address) {
        return Str.of(address(address).getHostAddress());
    }

    public static Term parseAddress(Term text) {
        try {
            return ok(address(InetAddress.getByName(text(text))));
        } catch (UnknownHostException | org.jerlang.erts.erlang.Error e) {
            return error(Atom.of("einval"));
        }
    }

    public static Term address(InetAddress address) {
        byte[] bytes = address.getAddress();
        if (address instanceof Inet4Address || bytes.length == 4) {
            return Tuple.of(
                Integer.of(bytes[0] & 0xFF),
                Integer.of(bytes[1] & 0xFF),
                Integer.of(bytes[2] & 0xFF),
                Integer.of(bytes[3] & 0xFF));
        }
        Tuple tuple = new Tuple(8);
        for (int index = 0; index < 8; index++) {
            int value = ((bytes[index * 2] & 0xFF) << 8) | (bytes[index * 2 + 1] & 0xFF);
            tuple.set(index + 1, Integer.of(value));
        }
        return tuple;
    }

    public static InetAddress address(Term term) {
        if (!(term instanceof Tuple)) {
            throw Error.badarg;
        }
        Tuple tuple = (Tuple) term;
        byte[] bytes;
        try {
            if (tuple.arity() == 4) {
                bytes = new byte[4];
                for (int index = 0; index < 4; index++) {
                    bytes[index] = (byte) tuple.element(index + 1).toInteger().toInt();
                }
            } else if (tuple.arity() == 8) {
                bytes = new byte[16];
                for (int index = 0; index < 8; index++) {
                    int value = tuple.element(index + 1).toInteger().toInt();
                    bytes[index * 2] = (byte) (value >> 8);
                    bytes[index * 2 + 1] = (byte) value;
                }
            } else {
                throw Error.badarg;
            }
            return InetAddress.getByAddress(bytes);
        } catch (UnknownHostException e) {
            throw Error.badarg;
        }
    }

    public static byte[] addressBytes(Term term) {
        if (!(term instanceof Tuple)) {
            throw Error.badarg;
        }
        Tuple tuple = (Tuple) term;
        if (tuple.arity() == 4) {
            byte[] bytes = new byte[4];
            for (int index = 0; index < 4; index++) {
                bytes[index] = (byte) tuple.element(index + 1).toInteger().toInt();
            }
            return bytes;
        }
        if (tuple.arity() == 8) {
            byte[] bytes = new byte[16];
            for (int index = 0; index < 8; index++) {
                int value = tuple.element(index + 1).toInteger().toInt();
                bytes[index * 2] = (byte) (value >> 8);
                bytes[index * 2 + 1] = (byte) value;
            }
            return bytes;
        }
        throw Error.badarg;
    }

    public static Atom reason(Throwable throwable) {
        if (throwable instanceof java.net.SocketTimeoutException) {
            return Atom.of("timeout");
        }
        if (throwable instanceof java.net.ConnectException) {
            return Atom.of("econnrefused");
        }
        if (throwable instanceof java.net.BindException) {
            return Atom.of("eaddrinuse");
        }
        if (throwable instanceof java.net.UnknownHostException) {
            return Atom.of("nxdomain");
        }
        if (throwable instanceof java.io.EOFException) {
            return Atom.of("closed");
        }
        if (throwable instanceof java.net.SocketException) {
            return Atom.of("closed");
        }
        return Atom.of("einval");
    }

    public static InetAddress bindAddress(InetOptions options) {
        if (options.ip != null) {
            try {
                return InetAddress.getByAddress(options.ip);
            } catch (UnknownHostException e) {
                throw Error.badarg;
            }
        }
        try {
            return InetAddress.getByName(options.inet6 ? "::" : "0.0.0.0");
        } catch (UnknownHostException e) {
            throw Error.badarg;
        }
    }
}
