package org.jerlang.stdlib.ets;

import java.util.concurrent.atomic.AtomicInteger;

import org.jerlang.type.Atom;
import org.jerlang.type.Integer;
import org.jerlang.type.List;
import org.jerlang.type.Term;
import org.jerlang.type.Tuple;

/**
 * Base class for all ETS tables.
 */
public abstract class Table {

    private static final AtomicInteger COUNTER = new AtomicInteger();

    private final Integer id;
    private final Type type;
    private final Access access;
    private final Atom name;
    private final boolean named_table;
    private final boolean compressed;
    private final int keypos;

    protected Table(Type type, Access access, Atom name, boolean named_table,
                    boolean compressed, int keypos) {
        this.id = Integer.of(COUNTER.incrementAndGet());
        this.type = type;
        this.access = access;
        this.name = name;
        this.named_table = named_table;
        this.compressed = compressed;
        this.keypos = keypos;
        TableRegistry.register(this);
    }

    public Access access() {
        return access;
    }

    public boolean compressed() {
        return compressed;
    }

    public Integer id() {
        return id;
    }

    /**
     * Remove the table and everything in it. The table identifier stops
     * working; a named table's name becomes free again.
     */
    public void delete() {
        TableRegistry.unregister(this);
    }

    /**
     * Remove every object with the given key. A key that is not in the table
     * is not an error, so this is a no-op then.
     */
    public abstract void delete(Term key);

    /**
     * Store one object. The key is the tuple element at this table's key
     * position; an object with a key already in the table replaces it.
     */
    public abstract void insert(Tuple object);

    /**
     * Position of the key in an object tuple, 1-based. 1 unless the table was
     * created with {keypos, N}.
     */
    public int keypos() {
        return keypos;
    }

    public abstract List lookup(Term key);

    /**
     * The object stored under Key, or null if there is none. The reference is
     * the table's own object, so a caller that changes it has to copy it
     * first: an update that fails halfway must not leave a half-updated
     * object in the table.
     */
    public abstract Tuple objectOf(Term key);

    /**
     * Every object in the table. The order is unspecified, as in OTP; a set
     * happens to return the objects in key order.
     */
    public abstract List tab2list();

    public Atom name() {
        return name;
    }

    public boolean named_table() {
        return named_table;
    }

    public Type type() {
        return type;
    }

}
