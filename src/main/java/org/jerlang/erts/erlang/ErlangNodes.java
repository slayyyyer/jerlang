package org.jerlang.erts.erlang;

import org.jerlang.erts.dist.Distribution;
import org.jerlang.type.Atom;
import org.jerlang.type.List;
import org.jerlang.type.Term;

public class ErlangNodes {

    private static final Atom CONNECTED = Atom.of("connected");
    private static final Atom VISIBLE = Atom.of("visible");
    private static final Atom HIDDEN = Atom.of("hidden");

    private ErlangNodes() {
    }

    public static Term dispatch(List params) {
        switch (params.length()) {
        case 0:
            return nodes_0();
        case 1:
            return nodes_1(params.head());
        default:
            throw Error.badarg;
        }
    }

    /**
     * Return the nodes this node is connected to.
     *
     * http://www.erlang.org/doc/man/erlang.html#nodes-0
     */
    public static List nodes_0() {
        return Distribution.nodes();
    }

    /**
     * Return a subset of the connected nodes. JErlang does not implement
     * hidden nodes, so `connected` and `visible` are the same list.
     *
     * http://www.erlang.org/doc/man/erlang.html#nodes-1
     */
    public static List nodes_1(Term type) {
        if (!(type instanceof Atom)) {
            throw Error.badarg;
        }
        Atom atom = (Atom) type;
        if (CONNECTED.equals(atom) || VISIBLE.equals(atom)) {
            return Distribution.nodes();
        }
        if (HIDDEN.equals(atom)) {
            return List.nil;
        }
        throw Error.badarg;
    }

}
