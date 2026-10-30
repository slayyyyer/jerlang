package org.jerlang.erts.erlang;

import org.jerlang.Aliases;
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
        switch (params.length()) {
        case 2:
            return monitor_2(params.head().toAtom(), params.tail().head());
        case 3:
            return monitor_3(params.head().toAtom(), params.tail().head(),
                params.tail().tail().head().toList());
        default:
            throw Error.badarg;
        }
    }

    /**
     * monitor(process, Target, Options). The `{alias, Mode}` option makes the
     * monitor reference an alias as well, so the monitored process can reply
     * to the reference instead of to the monitoring pid; gen_server:call
     * relies on that to hide the caller.
     *
     * http://www.erlang.org/doc/man/erlang.html#monitor-3
     */
    public static Term monitor_3(Atom type, Term target, List options) {
        boolean alias = false;
        while (options.length() > 0) {
            Term option = options.head();
            options = options.tail();
            if (!(option instanceof Tuple) || ((Tuple) option).arity() != 2) {
                throw Error.badarg;
            }
            Term key = ((Tuple) option).element(1);
            Term value = ((Tuple) option).element(2);
            if (!(key instanceof Atom) || !(value instanceof Atom)) {
                throw Error.badarg;
            }
            switch (key.toString()) {
            case "alias":
                switch (value.toString()) {
                case "demonitor":
                    // The alias ends with the monitor.
                    alias = true;
                    break;
                case "reply_demonitor":
                    // OTP also ends the alias on the first reply. The monitor
                    // is removed either way, so treating both the same only
                    // means a second reply is still delivered.
                    alias = true;
                    break;
                case "none":
                    break;
                default:
                    throw Error.badarg;
                }
                break;
            default:
                throw Error.badarg;
            }
        }
        Term reference = monitor_2(type, target);
        if (alias) {
            if (!(reference instanceof Reference)) {
                throw Error.badarg;
            }
            Aliases.register((Reference) reference, ProcessSignals.self().pid());
        }
        return reference;
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
