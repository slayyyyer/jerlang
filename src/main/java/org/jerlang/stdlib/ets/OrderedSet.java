package org.jerlang.stdlib.ets;

import java.util.TreeMap;

import org.jerlang.type.Atom;
import org.jerlang.type.List;
import org.jerlang.type.Term;
import org.jerlang.type.TermOrder;
import org.jerlang.type.Tuple;

/**
 * An ETS ordered_set.
 *
 * ordered_set is the one table type whose keys compare with Erlang term order
 * and not with exact equality, so 1 and 1.0 are the same key and a later
 * insert replaces the earlier object. That is also why the table can return
 * its objects in key order, which set does not promise.
 */
public class OrderedSet extends Table {

    private final TreeMap<Term, Tuple> elements;

    public OrderedSet(Access access, Atom name, boolean named_table, boolean compressed,
                      int keypos) {
        super(Type.ORDERED_SET, access, name, named_table, compressed, keypos);
        elements = new TreeMap<>(TermOrder::compare);
    }

    @Override
    public void delete(Term key) {
        synchronized (lock()) {
            elements.remove(key);
        }
    }

    @Override
    public void insert(Tuple object) {
        // Store a copy: set_tuple_element updates a tuple in place, so keeping
        // the caller's tuple would let a later setelement change the table.
        synchronized (lock()) {
            elements.put(object.element(keypos()), new Tuple(object));
        }
    }

    @Override
    public List lookup(Term key) {
        synchronized (lock()) {
            Tuple element = elements.get(key);
            if (element == null) {
                return List.nil;
            }
            return List.of(new Tuple(element));
        }
    }

    @Override
    public Tuple objectOf(Term key) {
        synchronized (lock()) {
            Tuple element = elements.get(key);
            return element == null ? null : element;
        }
    }

    @Override
    public java.util.List<Tuple> objects() {
        synchronized (lock()) {
            return new java.util.ArrayList<>(elements.values());
        }
    }

    @Override
    public List tab2list() {
        // Ascending key order, which is what an ordered_set guarantees and
        // what tab2list reports for it.
        synchronized (lock()) {
            List result = List.nil;
            for (Tuple element : elements.descendingMap().values()) {
                // Copy like set does: set_tuple_element updates a tuple in place,
                // so the table must not hand out the tuple it stores.
                result = new List(new Tuple(element), result);
            }
            return result;
        }
    }

}
