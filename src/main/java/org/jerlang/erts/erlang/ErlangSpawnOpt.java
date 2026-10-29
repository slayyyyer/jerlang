package org.jerlang.erts.erlang;

import org.jerlang.type.List;
import org.jerlang.type.Term;

public class ErlangSpawnOpt {

    private ErlangSpawnOpt() {
    }

    public static Term dispatch(List params) {
        switch (params.length()) {
        case 2:
            return ErlangSpawn.spawn_opt_2(params.head().toFun(),
                params.tail().head().toList());
        case 3:
            return ErlangSpawn.spawn_opt_3(params.head().toAtom(),
                params.tail().head().toFun(), params.tail().tail().head().toList());
        case 4:
            return ErlangSpawn.spawn_opt_4(params.head().toAtom(),
                params.tail().head().toAtom(),
                params.tail().tail().head().toList(),
                params.tail().tail().tail().head().toList());
        case 5:
            return ErlangSpawn.spawn_opt_5(params.head().toAtom(),
                params.tail().head().toAtom(),
                params.tail().tail().head().toAtom(),
                params.tail().tail().tail().head().toList(),
                params.tail().tail().tail().tail().head().toList());
        default:
            throw Error.badarg;
        }
    }

}
