package org.jerlang.stdlib.ets;

import java.util.TreeMap;

import org.jerlang.type.Atom;
import org.jerlang.type.List;
import org.jerlang.type.Term;
import org.jerlang.type.TermOrder;
import org.jerlang.type.Tuple;

public class Set extends Table {

    /**
     * Keyed by the object's key in Erlang term order, which is what a sorted
     * ETS set looked like through totallylazy. Term.compareTo is a stub that
     * returns 0, so the comparator has to be explicit: with the default
     * comparator every key looked equal and a table could only ever hold one
     * object.
     *
     * Kept as a map from key to element so lookup can return the element that
     * was stored, not the key that was used to find it.
     */
    private final TreeMap<Term, Term> elements;

    public Set(Access access, Atom name, boolean named_table, boolean compressed,
               int keypos) {
        super(Type.SET, access, name, named_table, compressed, keypos);
        elements = new TreeMap<>(Set::compareKeys);
    }

    /**
     * A set compares keys with exact equality, not with ==: 1 and 1.0 are
     * different keys, which OTP confirms. TermOrder compares them equal, so
     * terms that tie there but are not exactly equal are ordered by type to
     * keep both in the table. The order is only an implementation detail of
     * the map; nothing exposes it.
     */
    private static int compareKeys(Term a, Term b) {
        int order = TermOrder.compare(a, b);
        if (order != 0 || a.equals(b)) {
            return order;
        }
        return a.getClass().getName().compareTo(b.getClass().getName());
    }

    @Override
    public void delete(Term key) {
        elements.remove(key);
    }

    @Override
    public void insert(Tuple object) {
        // Store a copy: set_tuple_element updates a tuple in place, so keeping
        // the caller's tuple would let a later setelement change the table.
        elements.put(object.element(keypos()), new Tuple(object));
    }

    @Override
    public List lookup(Term key) {
        Term element = elements.get(key);
        if (element == null) {
            return List.nil;
        } else {
            return List.of(new Tuple(element.toTuple()));
        }
    }

    @Override
    public Tuple objectOf(Term key) {
        Term object = elements.get(key);
        return object == null ? null : object.toTuple();
    }

    @Override
    public java.util.List<Tuple> objects() {
        java.util.List<Tuple> result = new java.util.ArrayList<>();
        for (Term element : elements.values()) {
            result.add((Tuple) element);
        }
        return result;
    }

    @Override
    public List tab2list() {
        // Descending iteration with a prepend builds an ascending list, which
        // is the order a sorted set already implies in this implementation.
        List result = List.nil;
        for (Term element : elements.descendingMap().values()) {
            result = new List(new Tuple(element.toTuple()), result);
        }
        return result;
    }

}
