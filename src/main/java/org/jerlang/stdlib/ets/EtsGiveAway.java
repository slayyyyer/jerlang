package org.jerlang.stdlib.ets;

import static org.jerlang.erts.erlang.Error.badarg;

import org.jerlang.Process;
import org.jerlang.ProcessOrPort;
import org.jerlang.ProcessRegistry;
import org.jerlang.type.Boolean;
import org.jerlang.type.List;
import org.jerlang.type.PID;
import org.jerlang.type.Term;

public class EtsGiveAway {

    private EtsGiveAway() {
    }

    public static Term dispatch(List params) {
        switch (params.length()) {
        case 3:
            return give_away_3(params.head(), params.tail().head(),
                params.tail().tail().head());
        default:
            throw badarg;
        }
    }

    /**
     * Transfers the table to another process. Only the current owner may give
     * the table away, the target has to be a live local process, and the
     * extra data for a table heir is not supported yet.
     *
     * http://www.erlang.org/doc/man/ets.html#give_away-3
     */
    public static Term give_away_3(Term tab, Term to, Term giftData) {
        Table table = TableRegistry.lookup(tab);
        if (table == null) {
            throw badarg;
        }
        if (!(to instanceof PID)) {
            throw badarg;
        }
        PID target = (PID) to;
        ProcessOrPort resolved = ProcessRegistry.resolve(target);
        if (!(resolved instanceof Process) || ((Process) resolved).isTerminated()) {
            throw badarg;
        }
        ProcessOrPort self = ProcessRegistry.self();
        if (!(self instanceof Process)) {
            throw badarg;
        }
        PID selfPid = ((Process) self).pid();
        PID owner = table.owner();
        if (owner == null || !owner.equals(selfPid) || selfPid.equals(target)) {
            throw badarg;
        }
        table.giveAway(target);
        // A gift is delivered as a message when the table has an heir; with
        // no heir model there is nothing to send, so giftData is unused.
        return Boolean.am_true;
    }

}
