package org.jerlang.stdlib.lists;

import org.jerlang.erts.erlang.Error;
import org.jerlang.type.Boolean;
import org.jerlang.type.List;
import org.jerlang.type.Term;

public class ListsMember {

    private ListsMember() {
    }

    public static Term dispatch(List params) {
        switch (params.length()) {
        case 2:
            Term element = params.head();
            List list = params.tail().head().toList();
            return member_2(element, list);
        default:
            throw Error.badarg;
        }
    }

    /**
     * Returns true if `element` matches some element of `list`. Matching uses
     * =:=, so 1 does not match 1.0. OTP implements this as a BIF, so
     * lists.beam only carries a nif_error placeholder for it.
     *
     * http://www.erlang.org/doc/man/lists.html#member-2
     */
    public static Term member_2(Term element, List list) {
        List current = list;
        while (current.length() > 0) {
            if (element.equals(current.head())) {
                return Boolean.am_true;
            }
            current = current.tail();
        }
        return Boolean.am_false;
    }

}
