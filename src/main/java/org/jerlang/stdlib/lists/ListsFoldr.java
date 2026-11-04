package org.jerlang.stdlib.lists;

import org.jerlang.erts.erlang.Error;
import org.jerlang.erts.erlang.FunApply;
import org.jerlang.type.List;
import org.jerlang.type.Term;

public class ListsFoldr {

    private ListsFoldr() {
    }

    public static Term dispatch(List params) {
        if (params.length() != 3) {
            throw Error.badarg;
        }
        return foldr_3(params.head(), params.tail().head(), params.tail().tail().head().toList());
    }

    /**
     * Calls Fun(Element, Acc) for every element from right to left.
     *
     * http://www.erlang.org/doc/man/lists.html#foldr-3
     */
    public static Term foldr_3(Term fun, Term init, List list) {
        Term acc = init;
        list = ListsReverse.reverse_1(list);
        while (list != List.nil) {
            acc = FunApply.apply(fun, List.of(list.head(), acc));
            list = list.tail();
        }
        return acc;
    }

}
