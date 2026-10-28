package org.jerlang.erts.dist;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.jerlang.Process;
import org.jerlang.ProcessOrPort;
import org.jerlang.ProcessRegistry;
import org.jerlang.erts.erlang.Error;
import org.jerlang.type.Atom;
import org.jerlang.type.List;
import org.jerlang.type.PID;
import org.jerlang.type.PidOrPortId;
import org.jerlang.type.Term;
import org.jerlang.type.Tuple;
import org.jerlang.vm.VirtualMachine;

/**
 * The nodes this virtual machine is connected to, and the entry points
 * for sending signals to them.
 *
 * Only outgoing connections are implemented: JErlang can connect to an
 * Erlang/OTP node and exchange messages over that connection, but it does
 * not listen for incoming connections yet.
 */
public final class Distribution {

    private static final Object CONNECT_LOCK = new Object();

    private static final Map<Atom, DistConnection> CONNECTIONS = new ConcurrentHashMap<>();

    private Distribution() {
    }

    /**
     * Start the distribution listener for a named node. Nodes without a
     * name can still make outgoing connections, but no node can find them.
     */
    public static void start() {
        DistListener.start();
    }

    /**
     * Stop the listener and close every connection.
     */
    public static void shutdown() {
        DistListener.stop();
        disconnectAll();
    }

    /**
     * Add a connection accepted by the listener. If an outgoing connection
     * to the same node is already open, keep it and close the new one.
     */
    static void add(DistConnection connection) {
        DistConnection existing = CONNECTIONS.putIfAbsent(connection.peer(), connection);
        if (existing != null && existing.isOpen()) {
            connection.close();
        } else {
            CONNECTIONS.put(connection.peer(), connection);
        }
    }

    /**
     * Connect to a node, or return true if a connection already exists.
     */
    public static boolean connect(Atom node) {
        if (node == null) {
            return false;
        }
        Atom local = VirtualMachine.instance().node();
        if (!DistNodeName.isValid(local) || !DistNodeName.isValid(node)) {
            return false;
        }
        if (node.equals(local)) {
            return true;
        }
        DistConnection existing = CONNECTIONS.get(node);
        if (existing != null && existing.isOpen()) {
            return true;
        }
        synchronized (CONNECT_LOCK) {
            existing = CONNECTIONS.get(node);
            if (existing != null && existing.isOpen()) {
                return true;
            }
            if (existing != null) {
                CONNECTIONS.remove(node);
            }
            try {
                DistConnection connection = DistConnection.open(node);
                CONNECTIONS.put(node, connection);
                return true;
            } catch (IOException | RuntimeException e) {
                if (Boolean.getBoolean("jerlang.dist.debug")) {
                    System.err.println("jerlang dist: can not connect to " + node + ": " + e);
                }
                return false;
            }
        }
    }

    public static boolean isConnected(Atom node) {
        DistConnection connection = CONNECTIONS.get(node);
        return connection != null && connection.isOpen();
    }

    /**
     * The nodes this node has a connection to, as erlang:nodes/0 reports it.
     */
    public static List nodes() {
        ArrayList<Atom> nodes = new ArrayList<>();
        for (Map.Entry<Atom, DistConnection> entry : CONNECTIONS.entrySet()) {
            if (entry.getValue().isOpen()) {
                nodes.add(entry.getKey());
            }
        }
        return List.of(nodes);
    }

    /**
     * Send `Message` to `Target`, which can be a PID, a registered name,
     * or a {Name, Node} tuple, as the send operator accepts it.
     */
    public static void send(Term target, PID from, Term message) {
        if (target instanceof PID) {
            PID pid = (PID) target;
            if (pid.isLocal()) {
                deliverLocal(pid, message);
            } else {
                sendRemote(pid.node(), pid, null, from, message);
            }
            return;
        }

        if (target instanceof Atom) {
            Atom name = (Atom) target;
            if (ProcessRegistry.instance().get(name) == null) {
                throw Error.badarg;
            }
            deliverLocal(name, message);
            return;
        }

        if (target instanceof Tuple) {
            Tuple tuple = (Tuple) target;
            if (tuple.arity() == 2 && tuple.element(1) instanceof Atom
                && tuple.element(2) instanceof Atom) {
                Atom name = tuple.element(1).toAtom();
                Atom node = tuple.element(2).toAtom();
                if (node.equals(VirtualMachine.instance().node())) {
                    if (ProcessRegistry.instance().get(name) == null) {
                        throw Error.badarg;
                    }
                    deliverLocal(name, message);
                } else {
                    sendRemote(node, null, name, from, message);
                }
                return;
            }
        }

        throw Error.badarg;
    }

    private static void sendRemote(Atom node, PID to, Atom name, PID from, Term message) {
        DistConnection connection = CONNECTIONS.get(node);
        if (connection == null || !connection.isOpen()) {
            if (!connect(node)) {
                // Sending to a node that can not be reached is not an error
                // in Erlang; the message is dropped.
                return;
            }
            connection = CONNECTIONS.get(node);
        }
        if (connection == null) {
            return;
        }
        try {
            if (to != null) {
                connection.sendPid(to, message);
            } else {
                connection.sendName(name, from != null ? from : new PID(0), message);
            }
        } catch (IOException e) {
            if (Boolean.getBoolean("jerlang.dist.debug")) {
                System.err.println("jerlang dist: can not send to " + node + ": " + e);
            }
            connection.close();
        }
    }

    static boolean hasLocal(PID pid) {
        return ProcessRegistry.resolve(pid) instanceof Process;
    }

    static boolean hasLocal(Atom name) {
        PidOrPortId id = ProcessRegistry.instance().get(name);
        return id != null && ProcessRegistry.resolve(id) instanceof Process;
    }

    static void deliverLocal(PID pid, Term message) {
        ProcessOrPort process = ProcessRegistry.resolve(pid);
        if (process instanceof Process) {
            ((Process) process).send(message);
        }
    }

    static void deliverLocal(Atom name, Term message) {
        PidOrPortId id = ProcessRegistry.instance().get(name);
        if (id == null) {
            return;
        }
        ProcessOrPort process = ProcessRegistry.resolve(id);
        if (process instanceof Process) {
            ((Process) process).send(message);
        }
    }

    public static void disconnect(Atom node) {
        DistConnection connection = CONNECTIONS.remove(node);
        if (connection != null) {
            connection.close();
        }
    }

    public static void disconnectAll() {
        for (Atom node : new ArrayList<>(CONNECTIONS.keySet())) {
            disconnect(node);
        }
    }

    /**
     * Called by a connection when its reader thread terminates.
     */
    static void remove(DistConnection connection) {
        CONNECTIONS.remove(connection.peer(), connection);
    }

}
