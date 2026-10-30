package org.jerlang.stdlib;

import org.jerlang.stdlib.ets.EtsAll;
import org.jerlang.stdlib.ets.EtsDelete;
import org.jerlang.stdlib.ets.EtsInsert;
import org.jerlang.stdlib.ets.EtsLookup;
import org.jerlang.stdlib.ets.EtsNew;
import org.jerlang.stdlib.ets.EtsTab2list;
import org.jerlang.stdlib.ets.EtsUpdateCounter;
import org.jerlang.stdlib.ets.EtsWhereis;
import org.jerlang.type.Atom;
import org.jerlang.type.List;
import org.jerlang.type.Term;

/**
 * = ets
 *
 * Built-In Term Storage
 *
 * Only the implemented functions are declared here. The registry exports one
 * function per declaration, so a declaration without an implementation class
 * would be an exported function that returns null.
 *
 * http://www.erlang.org/doc/man/ets.html
 */
public class Ets {

    public static Term all() {
        return EtsAll.all_0();
    }

    public static Term delete(Term tab) {
        return EtsDelete.delete_1(tab);
    }

    public static Term delete(Term tab, Term key) {
        return EtsDelete.delete_2(tab, key);
    }

    public static Term insert(Term tab, Term objectOrObjects) {
        return EtsInsert.insert_2(tab, objectOrObjects);
    }

    public static List lookup(Term tab, Term key) {
        return EtsLookup.lookup_2(tab, key);
    }

    public static Term _new(Atom name, List options) {
        return EtsNew.new_2(name, options);
    }

    public static List tab2list(Term tab) {
        return EtsTab2list.tab2list_1(tab);
    }

    public static Term update_counter(Term tab, Term key, Term updateOp) {
        return EtsUpdateCounter.update_counter_3(tab, key, updateOp);
    }

    /**
     * http://www.erlang.org/doc/man/ets.html#whereis-1
     */
    public static Term whereis(Term tab) {
        return EtsWhereis.whereis_1(tab);
    }

    public static Term update_counter(Term tab, Term key, Term updateOp, Term defaultValue) {
        return EtsUpdateCounter.update_counter_4(tab, key, updateOp, defaultValue);
    }

}
