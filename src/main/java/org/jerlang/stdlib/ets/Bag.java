package org.jerlang.stdlib.ets;

import java.util.ArrayList;
import java.util.TreeMap;

import org.jerlang.type.Atom;
import org.jerlang.type.List;
import org.jerlang.type.Term;
import org.jerlang.type.Tuple;

/**
 * An ETS bag or duplicate_bag.
 *
 * A bag keeps at most one copy of an object per key; a duplicate_bag keeps
 * every copy. Both return every object stored under a key from lookup/2 and
 * delete every one of them from delete/2. Object tuples are copied on the way
 * in, the same way set and ordered_set do, so a later setelement/3 can not
 * mutate a stored object.
 */
public class Bag extends Table {

    private final TreeMap<Term, java.util.List<Tuple>> elements =
        new TreeMap<>(Set::compareKeys);

    public Bag(Type type, Access access, Atom name, boolean named_table,
               boolean compressed, int keypos) {
        super(type, access, name, named_table, compressed, keypos);
    }

    @Override
    public void delete(Term key) {
        synchronized (lock()) {
            elements.remove(key);
        }
    }

    @Override
    public void insert(Tuple object) {
        synchronized (lock()) {
            Term key = object.element(keypos());
            java.util.List<Tuple> bucket = elements.get(key);
            if (bucket == null) {
                bucket = new ArrayList<>();
                elements.put(key, bucket);
            }
            Tuple copy = new Tuple(object);
            if (type() != Type.DUPLICATE_BAG && bucket.contains(copy)) {
                return;
            }
            bucket.add(copy);
        }
    }

    @Override
    public List lookup(Term key) {
        synchronized (lock()) {
            java.util.List<Tuple> bucket = elements.get(key);
            List result = List.nil;
            if (bucket != null) {
                for (int index = bucket.size() - 1; index >= 0; index--) {
                    result = new List(new Tuple(bucket.get(index)), result);
                }
            }
            return result;
        }
    }

    @Override
    public Tuple objectOf(Term key) {
        synchronized (lock()) {
            java.util.List<Tuple> bucket = elements.get(key);
            return bucket == null || bucket.isEmpty() ? null : bucket.get(0);
        }
    }

    @Override
    public java.util.List<Tuple> objects() {
        synchronized (lock()) {
            java.util.List<Tuple> result = new ArrayList<>();
            for (java.util.List<Tuple> bucket : elements.values()) {
                for (Tuple object : bucket) {
                    result.add(new Tuple(object));
                }
            }
            return result;
        }
    }

    @Override
    public List tab2list() {
        List result = List.nil;
        java.util.List<Tuple> objects = objects();
        for (int index = objects.size() - 1; index >= 0; index--) {
            result = new List(objects.get(index), result);
        }
        return result;
    }

    @Override
    public void remove(Tuple object) {
        synchronized (lock()) {
            Term key = object.element(keypos());
            java.util.List<Tuple> bucket = elements.get(key);
            if (bucket == null) {
                return;
            }
            bucket.remove(object);
            if (bucket.isEmpty()) {
                elements.remove(key);
            }
        }
    }

}
