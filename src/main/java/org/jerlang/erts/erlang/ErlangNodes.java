package org.jerlang.erts.erlang;

import org.jerlang.erts.dist.Distribution;
import org.jerlang.type.List;
import org.jerlang.type.Term;

public class ErlangNodes {

    private ErlangNodes() {
    }

    public static Term dispatch(List params) {
        switch (params.length()) {
        case 0:
            return nodes_0();
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

}
