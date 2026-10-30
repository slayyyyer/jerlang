package org.jerlang.stdlib.ets;

import static org.jerlang.erts.erlang.Error.badarg;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;

import org.jerlang.type.Integer;
import org.jerlang.type.Term;
import org.jerlang.type.Tuple;

public class EtsUpdateCounter {

    private EtsUpdateCounter() {
    }

    public static Term dispatch(org.jerlang.type.List params) {
        switch (params.length()) {
        case 3:
            Term tab = params.head();
            params = params.tail();
            Term key = params.head();
            params = params.tail();
            return update_counter_3(tab, key, params.head());
        case 4:
            tab = params.head();
            params = params.tail();
            key = params.head();
            params = params.tail();
            Term updateOp = params.head();
            params = params.tail();
            return update_counter_4(tab, key, updateOp, params.head());
        default:
            throw badarg;
        }
    }

    /**
     * Increments the counters at the given positions of the object with key
     * Key, creating the object from Default when the key is missing.
     *
     * http://www.erlang.org/doc/man/ets.html#update_counter-3
     */
    public static Term update_counter_3(Term tab, Term key, Term updateOp) {
        return update(tab, key, updateOp, null);
    }

    /**
     * http://www.erlang.org/doc/man/ets.html#update_counter-4
     */
    public static Term update_counter_4(Term tab, Term key, Term updateOp, Term defaultValue) {
        return update(tab, key, updateOp, defaultValue);
    }

    private static Term update(Term tab, Term key, Term updateOp, Term defaultValue) {
        Table table = TableRegistry.lookup(tab);
        if (table == null) {
            throw badarg;
        }

        // A single operation returns its value; a list of operations returns
        // a list of values, even when it holds only one operation.
        boolean asList = updateOp instanceof org.jerlang.type.List;
        List<Operation> operations = parse(asList
            ? updateOp.toList() : org.jerlang.type.List.of(updateOp), table.keypos());

        // Read, modify and write under the table lock. Without it two
        // processes incrementing the same key could both read the old value
        // and one increment would be lost, which OTP does not allow.
        synchronized (table.lock()) {
            Tuple stored = table.objectOf(key);
            Tuple object;
            if (stored == null) {
                if (defaultValue == null) {
                    // OTP refuses to invent an object without a Default.
                    throw badarg;
                }
                object = fromDefault(table.keypos(), key, defaultValue, operations);
            } else {
                object = new Tuple(stored);
            }

            List<Term> values = new ArrayList<>();
            for (Operation operation : operations) {
                if (operation.position > object.arity()) {
                    throw badarg;
                }
                Term counter = object.element(operation.position);
                if (!(counter instanceof Integer)) {
                    throw badarg;
                }
                BigInteger next = ((Integer) counter).toBigInteger().add(operation.increment);
                if (operation.bounded) {
                    boolean over = operation.increment.signum() < 0
                        ? next.compareTo(operation.threshold) < 0
                        : next.compareTo(operation.threshold) > 0;
                    if (over) {
                        next = operation.setValue;
                    }
                }
                Integer value = new Integer(next);
                object.set(operation.position, value);
                values.add(value);
            }

            // The object was built from a copy, so a failed operation above
            // leaves the table untouched.
            table.insert(object);

            if (!asList) {
                return values.get(0);
            }
            org.jerlang.type.List result = org.jerlang.type.List.nil;
            for (int index = values.size() - 1; index >= 0; index--) {
                result = new org.jerlang.type.List(values.get(index), result);
            }
            return result;
        }
    }

    /**
     * Builds the object that does not exist yet from the Default object. OTP
     * keeps the rest of the Default tuple as given but sets its key element to
     * Key, so a Default whose key does not match Key still creates an object
     * findable by Key.
     */
    private static Tuple fromDefault(int keypos, Term key, Term defaultValue,
                                     List<Operation> operations) {
        if (!(defaultValue instanceof Tuple)) {
            throw badarg;
        }
        Tuple object = new Tuple((Tuple) defaultValue);
        if (object.arity() < keypos) {
            throw badarg;
        }
        for (Operation operation : operations) {
            if (operation.position > object.arity()) {
                throw badarg;
            }
        }
        object.set(keypos, key);
        return object;
    }

    private static List<Operation> parse(org.jerlang.type.List updateOps, int keypos) {
        List<Operation> operations = new ArrayList<>();
        while (updateOps.length() > 0) {
            Term term = updateOps.head();
            if (!(term instanceof Tuple)) {
                throw badarg;
            }
            Tuple updateOp = (Tuple) term;
            if (updateOp.arity() != 2 && updateOp.arity() != 4) {
                throw badarg;
            }
            int position = position(updateOp.element(1), keypos);
            BigInteger increment = integer(updateOp.element(2));
            if (updateOp.arity() == 2) {
                operations.add(new Operation(position, increment, null, null));
            } else {
                operations.add(new Operation(position, increment,
                    integer(updateOp.element(3)), integer(updateOp.element(4))));
            }
            updateOps = updateOps.tail();
        }
        return operations;
    }

    /**
     * OTP rejects position 1 and the key position. The first rule is about
     * the tuple, the second about the table.
     */
    private static int position(Term term, int keypos) {
        if (!(term instanceof Integer)) {
            throw badarg;
        }
        int position = ((Integer) term).toInt();
        if (position < 2 || position == keypos) {
            throw badarg;
        }
        return position;
    }

    private static BigInteger integer(Term term) {
        if (!(term instanceof Integer)) {
            throw badarg;
        }
        return ((Integer) term).toBigInteger();
    }

    private static final class Operation {

        private final int position;
        private final BigInteger increment;
        private final boolean bounded;
        private final BigInteger threshold;
        private final BigInteger setValue;

        private Operation(int position, BigInteger increment, BigInteger threshold,
                          BigInteger setValue) {
            this.position = position;
            this.increment = increment;
            this.bounded = threshold != null;
            this.threshold = threshold;
            this.setValue = setValue;
        }

    }

}
