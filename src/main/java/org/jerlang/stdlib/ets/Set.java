package org.jerlang.stdlib.ets;

import java.util.TreeMap;

import org.jerlang.type.Atom;
import org.jerlang.type.List;
import org.jerlang.type.Term;

public class Set extends Table {

    /**
     * Keyed by the term itself, in term order, which is what a sorted ETS
     * set looked like through totallylazy. Kept as a map from key to element
     * so lookup can return the element that was stored, not the key that was
     * used to find it.
     */
    private final TreeMap<Term, Term> elements;

    public Set(Access access, Atom name, boolean named_table, boolean compressed) {
        super(Type.SET, access, name, named_table, compressed);
        elements = new TreeMap<>();
    }

    @Override
    public List lookup(Term key) {
        Term element = elements.get(key);
        if (element == null) {
            return List.nil;
        } else {
            return List.of(element);
        }
    }

}
