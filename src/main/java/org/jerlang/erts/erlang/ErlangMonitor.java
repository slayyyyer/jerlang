package org.jerlang.erts.erlang;

import org.jerlang.ProcessRegistry;
import org.jerlang.ProcessSignals;
import org.jerlang.type.Atom;
import org.jerlang.type.List;
import org.jerlang.type.PID;
import org.jerlang.type.PidOrPortId;
import org.jerlang.type.Reference;
import org.jerlang.type.Term;
import org.jerlang.type.Tuple;

public class ErlangMonitor {

    private ErlangMonitor() {
    }

    public static Term dispatch(List params) {
        if (params.length() != 2) {
            throw Error.badarg;
        }
        return monitor_2(params.head().toAtom(), params.tail().head());
    }

    /**
     * Starts monitoring a process. The monitor is identified by a reference
     * that is used in the DOWN message when the process terminates.
     *
     * http://www.erlang.org/doc/man/erlang.html#monitor-2
     */
    public static Term monitor_2(Atom type, Term target) {
        if (!"process".equals(type.toString())) {
            throw Error.badarg;
        }
        if (target instanceof Tuple) {
            // {Name, Node} monitors a registered name, possibly on another
            // node; a bare name only works on this node.
            Tuple nameAndNode = (Tuple) target;
            if (nameAndNode.arity() == 2 && nameAndNode.element(1) instanceof Atom
                && nameAndNode.element(2) instanceof Atom) {
                return ProcessSignals.self().monitor(nameAndNode.element(1).toAtom(),
                    nameAndNode.element(2).toAtom());
            }
            throw Error.badarg;
        }
        return ProcessSignals.self().monitor(process(target));
    }

    private static PID process(Term target) {
        if (target instanceof PID) {
            return (PID) target;
        }
        if (target instanceof Atom) {
            PidOrPortId id = ProcessRegistry.whereis((Atom) target);
            if (id instanceof PID) {
                return (PID) id;
            }
        }
        throw Error.badarg;
    }

    static Reference reference(Term term) {
        if (!(term instanceof Reference)) {
            throw Error.badarg;
        }
        return (Reference) term;
    }

}
