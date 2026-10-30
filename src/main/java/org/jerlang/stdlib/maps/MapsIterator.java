package org.jerlang.stdlib.maps;

import org.jerlang.erts.erlang.Error;
import org.jerlang.type.Atom;
import org.jerlang.type.List;
import org.jerlang.type.Map;
import org.jerlang.type.Term;
import org.jerlang.type.Tuple;

public class MapsIterator {

    /**
     * The tag that identifies an iterator term. The iterator is opaque:
     * callers only pass it back to maps:next/1, so a tagged tuple with the
     * remaining pairs is enough.
     */
    static final Atom TAG = Atom.of("map_iterator");

    private MapsIterator() {
    }

    public static Term dispatch(List params) {
        switch (params.length()) {
        case 1:
            return iterator_1(params.head().toMap());
        default:
            throw Error.badarg;
        }
    }

    /**
     * http://www.erlang.org/doc/man/maps.html#iterator-1
     */
    public static Term iterator_1(Map map) {
        List remaining = List.nil;
        java.util.List<Term> keys = MapsToList.orderedKeys(map);
        for (int index = keys.size() - 1; index >= 0; index--) {
            Term key = keys.get(index);
            remaining = new List(Tuple.of(key, map.get(key)), remaining);
        }
        return Tuple.of(TAG, remaining);
    }

    /**
     * The pairs still to visit, or null when the term is not an iterator.
     */
    static List remaining(Term iterator) {
        if (!(iterator instanceof Tuple)) {
            return null;
        }
        Tuple tuple = (Tuple) iterator;
        if (tuple.arity() != 2 || !TAG.equals(tuple.element(1))) {
            return null;
        }
        Term rest = tuple.element(2);
        return rest instanceof List ? (List) rest : null;
    }

}
