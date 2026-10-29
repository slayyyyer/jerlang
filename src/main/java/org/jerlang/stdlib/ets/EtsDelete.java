package org.jerlang.stdlib.ets;

import static org.jerlang.erts.erlang.Error.badarg;

import org.jerlang.type.Boolean;
import org.jerlang.type.List;
import org.jerlang.type.Term;

public class EtsDelete {

    private EtsDelete() {
    }

    public static Term dispatch(List params) {
        switch (params.length()) {
        case 1:
            return delete_1(params.head());
        case 2:
            Term tab = params.head();
            params = params.tail();
            return delete_2(tab, params.head());
        default:
            throw badarg;
        }
    }

    /**
     * Deletes the entire table Tab. The identifier stops working; looking it
     * up afterwards is badarg, the same as any other table that does not
     * exist.
     *
     * http://www.erlang.org/doc/man/ets.html#delete-1
     */
    public static Term delete_1(Term tab) {
        table(tab).delete();
        return Boolean.am_true;
    }

    /**
     * Deletes every object with the key Key from the table Tab. A key that is
     * not in the table is not an error.
     *
     * http://www.erlang.org/doc/man/ets.html#delete-2
     */
    public static Term delete_2(Term tab, Term key) {
        table(tab).delete(key);
        return Boolean.am_true;
    }

    private static Table table(Term tab) {
        Table table = TableRegistry.lookup(tab);
        if (table == null) {
            throw badarg;
        }
        return table;
    }

}
