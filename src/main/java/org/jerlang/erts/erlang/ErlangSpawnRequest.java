package org.jerlang.erts.erlang;

import org.jerlang.ProcessSignals;
import org.jerlang.erts.dist.SpawnRequests;
import org.jerlang.type.Atom;
import org.jerlang.type.List;
import org.jerlang.type.Reference;
import org.jerlang.type.Term;

/**
 * Asynchronously send a spawn request to a node. The caller receives the
 * result as a message:
 *
 *   {spawn_reply, ReqId, ok, Pid}
 *   {spawn_reply, ReqId, error, Reason}
 *
 * The message can be changed with the `reply_tag` and `reply` options.
 *
 * http://www.erlang.org/doc/man/erlang.html#spawn_request-5
 */
public class ErlangSpawnRequest {

    private ErlangSpawnRequest() {
    }

    public static Term dispatch(List params) {
        switch (params.length()) {
        case 5:
            Atom node = params.head().toAtom();
            params = params.tail();
            Atom module = params.head().toAtom();
            params = params.tail();
            Atom function = params.head().toAtom();
            params = params.tail();
            List args = params.head().toList();
            params = params.tail();
            List options = params.head().toList();
            return spawn_request_5(node, module, function, args, options);
        default:
            throw Error.badarg;
        }
    }

    public static Reference spawn_request_5(Atom node, Atom module, Atom function, List args,
        List options) {
        return SpawnRequests.request(ProcessSignals.self().pid(), node, module, function, args,
            options, null);
    }

}
