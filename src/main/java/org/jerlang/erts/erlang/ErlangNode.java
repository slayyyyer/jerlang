package org.jerlang.erts.erlang;

import org.jerlang.type.Atom;
import org.jerlang.type.List;
import org.jerlang.type.PID;
import org.jerlang.type.Reference;
import org.jerlang.type.Term;
import org.jerlang.vm.VirtualMachine;

public class ErlangNode {

    private ErlangNode() {
    }

    public static Term dispatch(List params) {
        switch (params.length()) {
        case 0:
            return node_0();
        case 1:
            return node_1(params.head());
        default:
            throw Error.badarg;
        }
    }

    /**
     * Return the name of the local node.
     *
     * http://www.erlang.org/doc/man/erlang.html#node-0
     */
    public static Atom node_0() {
        return VirtualMachine.instance().node();
    }

    /**
     * Return the node where the given PID or reference was created.
     *
     * http://www.erlang.org/doc/man/erlang.html#node-1
     */
    public static Atom node_1(Term term) {
        if (term instanceof PID) {
            return ((PID) term).node();
        }
        if (term instanceof Reference) {
            return ((Reference) term).node();
        }
        throw Error.badarg;
    }

}
