package org.jerlang.erts.erlang;

import org.jerlang.ProcessSignals;
import org.jerlang.erts.dist.SpawnRequests;
import org.jerlang.type.Atom;
import org.jerlang.type.List;
import org.jerlang.type.Reference;
import org.jerlang.type.Term;

/**
 * Give up a spawn request whose reply has not arrived yet. Returns true
 * when the caller was still waiting for the reply.
 *
 * Only the process that made the request can abandon it.
 *
 * http://www.erlang.org/doc/man/erlang.html#spawn_request_abandon-1
 */
public class ErlangSpawnRequestAbandon {

    private ErlangSpawnRequestAbandon() {
    }

    public static Term dispatch(List params) {
        switch (params.length()) {
        case 1:
            return spawn_request_abandon_1(params.head().toReference());
        default:
            throw Error.badarg;
        }
    }

    public static Term spawn_request_abandon_1(Reference requestId) {
        return SpawnRequests.abandon(ProcessSignals.self().pid(), requestId);
    }

}
