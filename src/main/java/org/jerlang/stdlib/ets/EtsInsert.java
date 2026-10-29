package org.jerlang.stdlib.ets;

import static org.jerlang.erts.erlang.Error.badarg;

import org.jerlang.type.Boolean;
import org.jerlang.type.List;
import org.jerlang.type.Term;
import org.jerlang.type.Tuple;

public class EtsInsert {

    private EtsInsert() {
    }

    public static Term dispatch(List params) {
        switch (params.length()) {
        case 2:
            Term tab = params.head();
            params = params.tail();
            return insert_2(tab, params.head());
        default:
            throw badarg;
        }
    }

    /**
     * Inserts the object Object into the table Tab, or every object when a
     * list of objects is given. If an object with the same key is already in
     * the table, it is replaced.
     *
     * An object is a tuple; the key is the tuple element at the table's key
     * position. A missing table, a non-tuple object, an object shorter than
     * the key position, or a list that contains one, is badarg, as in OTP.
     *
     * http://www.erlang.org/doc/man/ets.html#insert-2
     */
    public static Term insert_2(Term tab, Term objectOrObjects) {
        Table table = TableRegistry.lookup(tab);
        if (table == null) {
            throw badarg;
        }
        if (objectOrObjects instanceof List) {
            List objects = objectOrObjects.toList();
            while (objects.length() > 0) {
                table.insert(object(objects.head(), table.keypos()));
                objects = objects.tail();
            }
        } else {
            table.insert(object(objectOrObjects, table.keypos()));
        }
        return Boolean.am_true;
    }

    private static Tuple object(Term term, int keypos) {
        if (!(term instanceof Tuple) || ((Tuple) term).arity() < keypos) {
            throw badarg;
        }
        return (Tuple) term;
    }

}
