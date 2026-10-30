package org.jerlang.stdlib.persistent_term;

import static org.jerlang.erts.erlang.Error.badarg;

import java.util.Map.Entry;

import org.jerlang.type.List;
import org.jerlang.type.Term;
import org.jerlang.type.Tuple;

public class PersistentTermGet {

    private PersistentTermGet() {
    }

    public static Term dispatch(List params) {
        switch (params.length()) {
        case 0:
            return get_0();
        case 1:
            return get_1(params.head());
        case 2:
            Term key = params.head();
            params = params.tail();
            return get_2(key, params.head());
        default:
            throw badarg;
        }
    }

    /**
     * Returns every term in the store, as a list of `{Key, Value}` tuples. The
     * order is unspecified.
     *
     * http://www.erlang.org/doc/man/persistent_term.html#get-0
     */
    public static List get_0() {
        List terms = List.nil;
        for (Entry<Term, Term> entry : TermStore.entries()) {
            terms = new List(Tuple.of(entry.getKey(), entry.getValue()), terms);
        }
        return terms;
    }

    /**
     * Returns the value stored under `key`. A key that is not in the store is
     * a badarg; get/2 is the form that has a default.
     *
     * http://www.erlang.org/doc/man/persistent_term.html#get-1
     */
    public static Term get_1(Term key) {
        Term value = TermStore.get(key);
        if (value == null) {
            throw badarg;
        }
        return value;
    }

    /**
     * Returns the value stored under `key`, or `default` when the key is not in
     * the store.
     *
     * http://www.erlang.org/doc/man/persistent_term.html#get-2
     */
    public static Term get_2(Term key, Term defaultValue) {
        Term value = TermStore.get(key);
        return value == null ? defaultValue : value;
    }

}
