package org.jerlang.erts.erlang;

import org.jerlang.type.List;
import org.jerlang.type.Term;

public class ErlangSpawnLink {

    private ErlangSpawnLink() {
    }

    public static Term dispatch(List params) {
        switch (params.length()) {
        case 1:
            return ErlangSpawn.spawn_link_1(params.head().toFun());
        case 3:
            return ErlangSpawn.spawn_link_3(params.head().toAtom(),
                params.tail().head().toAtom(), params.tail().tail().head().toList());
        case 4:
            return ErlangSpawn.spawn_link_4(params.head().toAtom(),
                params.tail().head().toAtom(), params.tail().tail().head().toAtom(),
                params.tail().tail().tail().head().toList());
        default:
            throw Error.badarg;
        }
    }

}
