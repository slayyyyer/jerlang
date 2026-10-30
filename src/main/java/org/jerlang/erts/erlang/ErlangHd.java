package org.jerlang.erts.erlang;

import org.jerlang.type.ImproperList;
import org.jerlang.type.List;
import org.jerlang.type.Term;

public class ErlangHd {

    private ErlangHd() {
    }

    public static Term dispatch(List params) {
        switch (params.length()) {
        case 1:
            return hd_1(params.head());
        default:
            throw Error.badarg;
        }
    }

    /**
     * Returns the head of List, that is, the first element.
     * Allowed in guard tests.
     * Failure: `badarg` if `list` is the empty list `[]` or not a list.
     *
     * A cons cell with a non-list tail is still a list to hd/1. gen_server
     * stores its timeout state as [TRef | TimeoutMsg] and calls hd/1 on it,
     * so rejecting an ImproperList here breaks every gen_server time-out.
     */
    public static Term hd_1(Term list) {
        if (list instanceof ImproperList) {
            return ((ImproperList) list).head();
        }
        if (!(list instanceof List) || list.equals(List.nil)) {
            throw Error.badarg;
        }
        return list.toList().head();
    }

}
