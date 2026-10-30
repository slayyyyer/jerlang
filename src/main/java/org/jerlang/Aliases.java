package org.jerlang;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.jerlang.type.PID;
import org.jerlang.type.Reference;
import org.jerlang.type.Term;

/**
 * The aliases of this node.
 *
 * An alias is a reference that can be used as a message destination: a send
 * to it is delivered to the process that created it. OTP creates one with
 * erlang:alias/0, and attaches one to a monitor with
 * monitor(process, Target, [{alias, demonitor}]), which is how gen_server:call
 * receives a reply without the server ever seeing the caller's pid.
 *
 * Sending to a reference that is not an active alias drops the message; it
 * is not an error, which is what OTP does and what makes a reply that arrives
 * after the monitor was removed harmless.
 */
public final class Aliases {

    private static final Map<Reference, PID> OWNERS = new ConcurrentHashMap<>();

    private Aliases() {
    }

    /**
     * Make a reference an active alias of the given process.
     */
    public static void register(Reference reference, PID owner) {
        OWNERS.put(reference, owner);
    }

    /**
     * Deactivate an alias. Returns true when it was active.
     */
    public static boolean deactivate(Reference reference) {
        return OWNERS.remove(reference) != null;
    }

    public static boolean active(Reference reference) {
        return OWNERS.containsKey(reference);
    }

    /**
     * Deliver a message to the alias owner, or drop it when the reference is
     * not an active alias.
     */
    public static void deliver(Reference reference, Term message) {
        PID owner = OWNERS.get(reference);
        if (owner == null) {
            return;
        }
        ProcessOrPort process = ProcessRegistry.resolve(owner);
        if (process instanceof Process) {
            ((Process) process).send(message);
        }
    }

    /**
     * Forget every alias, for a fresh virtual machine.
     */
    public static void reset() {
        OWNERS.clear();
    }

}
