package org.jerlang.stdlib.ets;

import static org.jerlang.erts.erlang.Error.badarg;

import org.jerlang.type.List;
import org.jerlang.type.Term;

public class EtsTab2list {

    private EtsTab2list() {
    }

    public static Term dispatch(List params) {
        switch (params.length()) {
        case 1:
            return tab2list_1(params.head());
        default:
            throw badarg;
        }
    }

    /**
     * Returns a list of all objects in the table Tab. The order is
     * unspecified, as in OTP.
     *
     * http://www.erlang.org/doc/man/ets.html#tab2list-1
     */
    public static List tab2list_1(Term tab) {
        Table table = TableRegistry.lookup(tab);
        if (table == null) {
            throw badarg;
        }
        return table.tab2list();
    }

}
