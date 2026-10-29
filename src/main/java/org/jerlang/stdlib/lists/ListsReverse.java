package org.jerlang.stdlib.lists;

import org.jerlang.erts.erlang.Error;
import org.jerlang.type.List;
import org.jerlang.type.Term;

public class ListsReverse {

    private ListsReverse() {
    }

    public static Term dispatch(List params) {
        switch (params.length()) {
        case 1:
            return reverse_1(params.head().toList());
        case 2:
            List list = params.head().toList();
            List tail = params.tail().head().toList();
            return reverse_2(list, tail);
        default:
            throw Error.badarg;
        }
    }

    /**
     * Returns a list with the elements in `list` in reverse order, followed by
     * `tail`. OTP implements this as a BIF, so lists.beam only carries a
     * nif_error placeholder for it.
     *
     * http://www.erlang.org/doc/man/lists.html#reverse-2
     */
    public static List reverse_2(List list, List tail) {
        List result = tail;
        List current = list;
        while (current.length() > 0) {
            result = new List(current.head(), result);
            current = current.tail();
        }
        return result;
    }

    /**
     * Returns a list with the elements in `list` in reverse order.
     *
     * http://www.erlang.org/doc/man/lists.html#reverse-1
     */
    public static List reverse_1(List list) {
        if (list.equals(List.nil)) {
            return list;
        }
        if (list.tail().equals(List.nil)) {
            return list;
        }
        List result = List.nil;
        while (!list.equals(List.nil)) {
            result = new List(list.head(), result);
            list = list.tail();
        }
        return result;
    }

}
