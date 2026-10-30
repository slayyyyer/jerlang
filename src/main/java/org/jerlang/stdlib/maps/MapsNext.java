package org.jerlang.stdlib.maps;

import org.jerlang.erts.erlang.Error;
import org.jerlang.type.Atom;
import org.jerlang.type.List;
import org.jerlang.type.Term;
import org.jerlang.type.Tuple;

public class MapsNext {

    private static final Atom NONE = Atom.of("none");

    private MapsNext() {
    }

    public static Term dispatch(List params) {
        switch (params.length()) {
        case 1:
            return next_1(params.head());
        default:
            throw Error.badarg;
        }
    }

    /**
     * Returns {Key, Value, NextIterator}, or none when the map has no more
     * elements.
     *
     * http://www.erlang.org/doc/man/maps.html#next-1
     */
    public static Term next_1(Term iterator) {
        List remaining = MapsIterator.remaining(iterator);
        if (remaining == null) {
            throw Error.badarg;
        }
        if (remaining.length() == 0) {
            return NONE;
        }
        Tuple pair = remaining.head().toTuple();
        return Tuple.of(pair.element(1), pair.element(2),
            Tuple.of(MapsIterator.TAG, remaining.tail()));
    }

}
