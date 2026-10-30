package org.jerlang.stdlib.persistent_term;

import static org.jerlang.erts.erlang.Error.badarg;

import org.jerlang.type.Boolean;
import org.jerlang.type.List;
import org.jerlang.type.Term;

public class PersistentTermPut {

    private PersistentTermPut() {
    }

    public static Term dispatch(List params) {
        if (params.length() != 2) {
            throw badarg;
        }
        Term key = params.head();
        params = params.tail();
        return put_2(key, params.head());
    }

    /**
     * Stores `value` under `key`, replacing any value already there. Returns
     * `true`, always, so a caller can match on it.
     *
     * http://www.erlang.org/doc/man/persistent_term.html#put-2
     */
    public static Term put_2(Term key, Term value) {
        TermStore.put(key, value);
        return Boolean.am_true;
    }

}
