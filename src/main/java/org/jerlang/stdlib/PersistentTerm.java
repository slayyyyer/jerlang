package org.jerlang.stdlib;

import org.jerlang.stdlib.persistent_term.PersistentTermErase;
import org.jerlang.stdlib.persistent_term.PersistentTermGet;
import org.jerlang.stdlib.persistent_term.PersistentTermInfo;
import org.jerlang.stdlib.persistent_term.PersistentTermPut;
import org.jerlang.type.Term;

/**
 * = persistent_term
 *
 * A global term store, shared by every process on the node.
 *
 * Term storage in the VM is unsuitable for two things: data that is read often
 * but written rarely, because every read pays for a copy, and data that has to
 * outlive the processes that use it, because a table dies with its owner. This
 * module keeps terms in a plain concurrent map, so a read is a lookup and the
 * term survives until it is erased or the VM stops.
 *
 * Only the implemented functions are declared here. The registry exports one
 * function per declaration, so a declaration without an implementation class
 * would be an exported function that returns null.
 *
 * http://www.erlang.org/doc/man/persistent_term.html
 */
public class PersistentTerm {

    /**
     * Every term in the store, as a list of `{Key, Value}` tuples. The order
     * is unspecified.
     */
    public static Term get() {
        return PersistentTermGet.get_0();
    }

    /**
     * The value stored under `key`. Raises `badarg` when the key is not in the
     * store; use get/2 when a missing key is not an error.
     */
    public static Term get(Term key) {
        return PersistentTermGet.get_1(key);
    }

    /**
     * The value stored under `key`, or `default` when the key is not in the
     * store.
     */
    public static Term get(Term key, Term defaultValue) {
        return PersistentTermGet.get_2(key, defaultValue);
    }

    /**
     * Store `value` under `key`, replacing any value already there. Returns
     * `true`, always, so a caller can match on it.
     */
    public static Term put(Term key, Term value) {
        return PersistentTermPut.put_2(key, value);
    }

    /**
     * Remove `key` and its value. Erasing a key that is not in the store is not
     * an error, so this returns `true` either way.
     */
    public static Term erase(Term key) {
        return PersistentTermErase.erase_1(key);
    }

    /**
     * Remove every term from the store.
     */
    public static Term erase() {
        return PersistentTermErase.erase_0();
    }

    /**
     * A map describing the store: `count` is the number of terms and `memory`
     * is the number of bytes they occupy.
     */
    public static Term info() {
        return PersistentTermInfo.info_0();
    }

}
