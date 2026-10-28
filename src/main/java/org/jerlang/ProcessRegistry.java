package org.jerlang;

import java.util.ArrayList;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.jerlang.type.Atom;
import org.jerlang.type.List;
import org.jerlang.type.PidOrPortId;

public class ProcessRegistry {

    private static final ProcessRegistry instance = new ProcessRegistry();
    private final Map<Atom, PidOrPortId> processes;
    private final Map<PidOrPortId, ProcessOrPort> id2process;

    private ThreadLocal<ProcessOrPort> process = new ThreadLocal<>();

    public ProcessRegistry() {
        processes = new ConcurrentHashMap<>();
        id2process = new ConcurrentHashMap<>();
    }

    public void cleanup() {
        processes.clear();
        id2process.clear();
        process = new ThreadLocal<>();
    }

    public PidOrPortId get(Atom name) {
        return processes.get(name);
    }

    public static ProcessRegistry instance() {
        return instance;
    }

    public static void register(Atom name, PidOrPortId id) {
        instance.processes.put(name, id);
    }

    public static void register(Process process) {
        instance.id2process.put(process.id(), process);
    }

    public static void registerPort(ProcessOrPort port) {
        instance.id2process.put(port.id(), port);
    }

    public static ProcessOrPort resolve(PidOrPortId id) {
        return instance.id2process.get(id);
    }

    /**
     * The id registered under a name, or null when the name is free.
     */
    public static PidOrPortId whereis(Atom name) {
        return instance.processes.get(name);
    }

    public static boolean unregister(Atom name) {
        return instance.processes.remove(name) != null;
    }

    /**
     * Every registered name, in no particular order.
     */
    public static List registered() {
        List names = List.nil;
        for (Atom name : instance.processes.keySet()) {
            names = new List(name, names);
        }
        return names;
    }

    public static boolean isAlive(PidOrPortId id) {
        ProcessOrPort process = instance.id2process.get(id);
        return process != null && process.state() != ProcessState.EXITING;
    }

    /**
     * Every process and port that is still registered, for signals that
     * apply to the whole node, such as a lost distribution connection.
     */
    public static java.util.List<ProcessOrPort> all() {
        return new ArrayList<>(instance.id2process.values());
    }

    /**
     * Forget a terminated process and release the names it held. Without
     * this, `whereis/1` would keep answering for a dead process.
     */
    public static void remove(PidOrPortId id) {
        instance.id2process.remove(id);
        instance.processes.values().removeIf(id::equals);
    }

    public static ProcessOrPort self() {
        return instance.process.get();
    }

    public static void self(ProcessOrPort process) {
        instance.process.set(process);
    }

}
