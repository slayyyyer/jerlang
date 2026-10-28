package org.jerlang.erts.dist;

import java.net.InetAddress;
import java.net.UnknownHostException;

import org.jerlang.type.Atom;

/**
 * Node names are atoms of the form name@host, for example
 * jerlang@localhost or peer@example.com.
 */
public final class DistNodeName {

    private DistNodeName() {
    }

    /**
     * The part before the @ sign, or null if the atom is not a node name.
     */
    public static String name(Atom node) {
        String string = node.toString();
        int at = string.indexOf('@');
        if (at <= 0 || at == string.length() - 1) {
            return null;
        }
        return string.substring(0, at);
    }

    /**
     * The part after the @ sign, or null if the atom is not a node name.
     */
    public static String host(Atom node) {
        String string = node.toString();
        int at = string.indexOf('@');
        if (at <= 0 || at == string.length() - 1) {
            return null;
        }
        return string.substring(at + 1);
    }

    /**
     * True if the atom is a name@host node name and not nonode@nohost.
     */
    public static boolean isValid(Atom node) {
        return node != null
            && name(node) != null
            && host(node) != null
            && !node.equals(Atom.of("nonode@nohost"));
    }

    /**
     * The host name used by -sname (short names) or -name (long names).
     */
    public static String localHost(boolean longNames) throws UnknownHostException {
        InetAddress address = InetAddress.getLocalHost();
        return longNames ? address.getCanonicalHostName() : address.getHostName();
    }

}
