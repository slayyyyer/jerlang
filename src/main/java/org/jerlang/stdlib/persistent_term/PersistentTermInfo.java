package org.jerlang.stdlib.persistent_term;

import static org.jerlang.erts.erlang.Error.badarg;

import org.jerlang.type.Atom;
import org.jerlang.type.Integer;
import org.jerlang.type.List;
import org.jerlang.type.Map;
import org.jerlang.type.Term;

public class PersistentTermInfo {

    private PersistentTermInfo() {
    }

    public static Term dispatch(List params) {
        if (params.length() != 0) {
            throw badarg;
        }
        return info_0();
    }

    /**
     * Returns a map with the number of terms in the store and the number of
     * bytes they occupy.
     *
     * Terms are JVM objects, so there is no byte count to report and `memory`
     * is always 0. It is present because the map has to have the shape OTP
     * documents, and a consumer that uses it to decide when to garbage collect
     * will simply never see pressure from this store.
     *
     * http://www.erlang.org/doc/man/persistent_term.html#info-0
     */
    public static Map info_0() {
        Map info = new Map();
        info.set(Atom.of("count"), Integer.of(TermStore.count().intValue()));
        info.set(Atom.of("memory"), Integer.of(0));
        return info;
    }

}
