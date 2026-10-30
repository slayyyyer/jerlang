package org.jerlang.stdlib.ets;

import java.util.concurrent.atomic.AtomicInteger;

import org.jerlang.Process;
import org.jerlang.ProcessOrPort;
import org.jerlang.ProcessRegistry;
import org.jerlang.type.Atom;
import org.jerlang.type.Integer;
import org.jerlang.type.List;
import org.jerlang.type.PID;
import org.jerlang.type.Term;
import org.jerlang.type.Tuple;

/**
 * Base class for all ETS tables.
 */
public abstract class Table {

    private static final AtomicInteger COUNTER = new AtomicInteger();

    // One lock per table. Processes run on several scheduler threads, so the
    // map that holds the objects is guarded by this instead of relying on the
    // scheduler serialising table access. update_counter holds it across the
    // read-modify-write, which is what makes the increment atomic.
    private final Object lock = new Object();

    private final Integer id;
    private final Type type;
    private final Access access;
    private final Atom name;
    private final boolean named_table;
    private final boolean compressed;
    private final int keypos;
    // The process that created the table, which is what ets:whereis/1
    // reports. Null when the table was created outside a process. It moves
    // with ets:give_away/3.
    private volatile PID owner;

    protected Table(Type type, Access access, Atom name, boolean named_table,
                    boolean compressed, int keypos) {
        this.id = Integer.of(COUNTER.incrementAndGet());
        this.type = type;
        this.access = access;
        this.name = name;
        this.named_table = named_table;
        this.compressed = compressed;
        this.keypos = keypos;
        ProcessOrPort self = ProcessRegistry.self();
        this.owner = self instanceof Process ? ((Process) self).pid() : null;
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

    /** Guard for the object store, held by every operation on the table. */
    final Object lock() {
        return lock;
    }

    /**
     * Remove the table and everything in it. The table identifier stops
     * working; a named table's name becomes free again. A table lives as long
     * as the process that owns it, so the runtime calls this when that process
     * terminates as well as for an explicit ets:delete/1.
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

    /**
     * Every object in the table in table order. A set has no order; an
     * ordered_set returns them by key.
     */
    public abstract java.util.List<Tuple> objects();

    public Atom name() {
        return name;
    }

    /**
     * The pid of the creating process, or null when there is none.
     */
    public PID owner() {
        return owner;
    }

    public boolean named_table() {
        return named_table;
    }

    /**
     * Hand the table to another process, for ets:give_away/3. The caller has
     * already checked that it owns the table and that the target is alive.
     */
    public void giveAway(PID newOwner) {
        // Serialized with the owner-death cleanup, so a table that is being
        // given away can not be destroyed by its previous owner's exit.
        synchronized (lock) {
            this.owner = newOwner;
        }
    }

    public Type type() {
        return type;
    }

}
