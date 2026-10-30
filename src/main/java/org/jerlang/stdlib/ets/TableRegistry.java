package org.jerlang.stdlib.ets;

import java.util.ArrayList;
import java.util.Collection;
import java.util.concurrent.ConcurrentHashMap;

import org.jerlang.erts.erlang.Error;
import org.jerlang.type.Atom;
import org.jerlang.type.PID;
import org.jerlang.type.Term;

public class TableRegistry {

    private static final TableRegistry INSTANCE = new TableRegistry();

    private ConcurrentHashMap<Term, Table> id2table;
    private ConcurrentHashMap<Atom, Table> name2table;

    private TableRegistry() {
        id2table = new ConcurrentHashMap<>();
        name2table = new ConcurrentHashMap<>();
    }

    public static Table lookup(Term tab) {
        if (tab instanceof Atom) {
            return INSTANCE.name2table.get(tab);
        } else {
            return INSTANCE.id2table.get(tab);
        }
    }

    /**
     * Publish a new table. A named table's name belongs to one table at a
     * time, so the name is claimed atomically: two processes racing to create
     * the same named table can not both succeed.
     */
    public static synchronized void register(Table table) {
        if (table.named_table()) {
            Table previous = INSTANCE.name2table.putIfAbsent(table.name(), table);
            if (previous != null) {
                throw Error.badarg;
            }
        }
        INSTANCE.id2table.put(table.id(), table);
    }

    public static Collection<Table> tables() {
        return INSTANCE.id2table.values();
    }

    public static synchronized void unregister(Table table) {
        INSTANCE.id2table.remove(table.id(), table);
        if (table.named_table()) {
            // Conditional, so deleting a table that was already gone can not
            // remove a newer table that has taken over its name.
            INSTANCE.name2table.remove(table.name(), table);
        }
    }

    /**
     * Delete every table owned by a process that has terminated. OTP destroys
     * a table with its owner; without this a named table would keep its name
     * after the owning gen_server crashed, and the restart would fail.
     */
    public static void deleteOwnedBy(PID pid) {
        for (Table table : new ArrayList<>(INSTANCE.id2table.values())) {
            synchronized (table.lock()) {
                if (pid.equals(table.owner())) {
                    table.delete();
                }
            }
        }
    }

}
