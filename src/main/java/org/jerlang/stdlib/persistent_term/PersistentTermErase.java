package org.jerlang.stdlib.persistent_term;

import static org.jerlang.erts.erlang.Error.badarg;

import org.jerlang.type.Atom;
import org.jerlang.type.Boolean;
import org.jerlang.type.List;
import org.jerlang.type.Term;

public class PersistentTermErase {

    private PersistentTermErase() {
    }

    public static Term dispatch(List params) {
        switch (params.length()) {
        case 0:
            return erase_0();
        case 1:
            return erase_1(params.head());
        default:
            throw badarg;
        }
    }

    /**
     * Removes every term from the store.
     *
     * http://www.erlang.org/doc/man/persistent_term.html#erase-0
     */
    public static Term erase_0() {
        TermStore.clear();
        return Atom.of("ok");
    }

    /**
     * Removes `key` and its value. A key that is not in the store is not an
     * error, so this returns `true` either way.
     *
     * http://www.erlang.org/doc/man/persistent_term.html#erase-1
     */
    public static Term erase_1(Term key) {
        TermStore.remove(key);
        return Boolean.am_true;
    }

}
