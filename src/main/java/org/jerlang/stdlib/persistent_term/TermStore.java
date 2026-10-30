package org.jerlang.stdlib.persistent_term;

import java.util.Map.Entry;
import java.util.concurrent.ConcurrentHashMap;

import org.jerlang.type.Term;

/**
 * The terms themselves. A concurrent map is the whole store: the module has no
 * processes, no ownership and no per-key notification.
 */
public class TermStore {

    private static final ConcurrentHashMap<Term, Term> TERMS = new ConcurrentHashMap<>();

    private TermStore() {
    }

    public static Long count() {
        return (long) TERMS.size();
    }

    public static Term get(Term key) {
        return TERMS.get(key);
    }

    public static Iterable<Entry<Term, Term>> entries() {
        return TERMS.entrySet();
    }

    public static void put(Term key, Term value) {
        TERMS.put(key, value);
    }

    public static void remove(Term key) {
        TERMS.remove(key);
    }

    public static void clear() {
        TERMS.clear();
    }

}
