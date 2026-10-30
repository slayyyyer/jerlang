package org.jerlang.erts.init;

import org.jerlang.Process;
import org.jerlang.ProcessPriority;
import org.jerlang.erts.erlang.Error;
import org.jerlang.type.Atom;
import org.jerlang.type.Boolean;
import org.jerlang.type.Integer;
import org.jerlang.type.Term;

/**
 * erlang:process_flag/2.
 *
 * The value can be a boolean, an atom or a map depending on the flag, so the
 * dispatch passes the term through instead of converting it to a boolean
 * first. An unknown flag is a badarg, as in OTP.
 */
public class ProcessFlag {

    private ProcessFlag() {
    }

    /**
     * Set a process flag. Returns the previous value, which is what
     * process_flag/2 returns to Erlang code.
     */
    public static Term process_flag(Process process, Atom flag, Term value) {
        switch (flag.toString()) {
        case "trap_exit":
            return Boolean.of(process.setTrapExit(booleanValue(flag, value)));
        case "monitor_nodes":
            return Boolean.of(process.monitorNodeEvents(booleanValue(flag, value)));
        case "priority":
            return priority(process, value).toAtom();
        case "message_queue_data":
            return process.setMessageQueueData(queueData(value));
        case "max_heap_size":
            return process.setMaxHeapSize(maxHeapSize(value));
        // Accepted for OTP compatibility. The runtime has no equivalent yet,
        // but a module that sets one must not crash the process.
        case "async_dist":
            return Boolean.am_false;
        case "sensitive":
            return Boolean.am_false;
        case "save_calls":
        case "min_heap_size":
        case "min_bin_vheap_size":
            return Integer.ZERO;
        case "error_handler":
            return Atom.of("default");
        default:
            throw Error.badarg;
        }
    }

    private static boolean booleanValue(Atom flag, Term value) {
        if (value.equals(Boolean.am_true)) {
            return true;
        }
        if (value.equals(Boolean.am_false)) {
            return false;
        }
        throw Error.badarg;
    }

    private static ProcessPriority priority(Process process, Term value) {
        if (!(value instanceof Atom)) {
            throw Error.badarg;
        }
        String name = value.toString();
        for (ProcessPriority priority : ProcessPriority.values()) {
            if (priority.toAtom().toString().equals(name)) {
                process.setPriority(priority);
                return priority;
            }
        }
        throw Error.badarg;
    }

    private static Atom queueData(Term value) {
        if (!(value instanceof Atom)) {
            throw Error.badarg;
        }
        switch (value.toString()) {
        case "on_heap":
        case "off_heap":
            return (Atom) value;
        default:
            throw Error.badarg;
        }
    }

    /**
     * max_heap_size takes a map with `size`, `kill` and `error_logger`.
     * A zero size means no limit, which is the default. The runtime keeps the
     * value for process_info; enforcement belongs to a real per-process heap.
     */
    private static Term maxHeapSize(Term value) {
        if (!(value instanceof org.jerlang.type.Map)) {
            throw Error.badarg;
        }
        org.jerlang.type.Map map = (org.jerlang.type.Map) value;
        Term size = map.get(Atom.of("size"));
        if (!(size instanceof Integer) || size.toInteger().toLong() < 0) {
            throw Error.badarg;
        }
        Term kill = map.get(Atom.of("kill"));
        Term errorLogger = map.get(Atom.of("error_logger"));
        if (kill != null && !(kill.equals(Boolean.am_true) || kill.equals(Boolean.am_false))) {
            throw Error.badarg;
        }
        if (errorLogger != null
            && !(errorLogger.equals(Boolean.am_true) || errorLogger.equals(Boolean.am_false))) {
            throw Error.badarg;
        }
        return new org.jerlang.type.Map(map);
    }

}
