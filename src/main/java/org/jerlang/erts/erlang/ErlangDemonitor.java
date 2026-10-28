package org.jerlang.erts.erlang;

import org.jerlang.ProcessSignals;
import org.jerlang.type.Atom;
import org.jerlang.type.Boolean;
import org.jerlang.type.List;
import org.jerlang.type.Reference;
import org.jerlang.type.Term;
import org.jerlang.type.Tuple;

public class ErlangDemonitor {

    private ErlangDemonitor() {
    }

    public static Term dispatch(List params) {
        switch (params.length()) {
        case 1:
            return demonitor_1(ErlangMonitor.reference(params.head()));
        case 2:
            return demonitor_2(ErlangMonitor.reference(params.head()),
                params.tail().head().toList());
        default:
            throw Error.badarg;
        }
    }

    /**
     * Stops monitoring a process.
     *
     * http://www.erlang.org/doc/man/erlang.html#demonitor-1
     */
    public static Term demonitor_1(Reference reference) {
        ProcessSignals.self().demonitor(reference, false);
        return Boolean.am_true;
    }

    /**
     * demonitor/2 accepts `flush`, which removes a DOWN message that was
     * already delivered, and `info`, which reports whether one was found.
     *
     * http://www.erlang.org/doc/man/erlang.html#demonitor-2
     */
    public static Term demonitor_2(Reference reference, List options) {
        boolean flush = false;
        boolean info = false;
        while (options.length() > 0) {
            Term option = options.head();
            options = options.tail();
            if (!(option instanceof Atom)) {
                throw Error.badarg;
            }
            switch (option.toString()) {
            case "flush":
                flush = true;
                break;
            case "info":
                info = true;
                break;
            default:
                throw Error.badarg;
            }
        }
        boolean found = ProcessSignals.self().demonitor(reference, flush);
        return info ? Tuple.of(Atom.of("info"), Boolean.of(found)) : Boolean.am_true;
    }

}
