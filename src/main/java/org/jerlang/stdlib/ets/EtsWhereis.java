package org.jerlang.stdlib.ets;

import org.jerlang.erts.erlang.Error;
import org.jerlang.type.Atom;
import org.jerlang.type.List;
import org.jerlang.type.PID;
import org.jerlang.type.Term;

public class EtsWhereis {

    private static final Atom UNDEFINED = Atom.of("undefined");

    private EtsWhereis() {
    }

    public static Term dispatch(List params) {
        switch (params.length()) {
        case 1:
            return whereis_1(params.head());
        default:
            throw Error.badarg;
        }
    }

    /**
     * Returns the pid of the process that owns the table, or `undefined` when
     * the table does not exist.
     *
     * http://www.erlang.org/doc/man/ets.html#whereis-1
     */
    public static Term whereis_1(Term tab) {
        Table table = TableRegistry.lookup(tab);
        if (table == null) {
            return UNDEFINED;
        }
        PID owner = table.owner();
        return owner == null ? UNDEFINED : owner;
    }

}
