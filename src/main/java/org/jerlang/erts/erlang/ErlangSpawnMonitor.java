package org.jerlang.erts.erlang;

import org.jerlang.type.List;
import org.jerlang.type.Term;

public class ErlangSpawnMonitor {

    private ErlangSpawnMonitor() {
    }

    public static Term dispatch(List params) {
        switch (params.length()) {
        case 1:
            return ErlangSpawn.spawn_monitor_1(params.head().toFun());
        case 2:
            return ErlangSpawn.spawn_monitor_2(params.head().toAtom(),
                params.tail().head().toFun());
        case 3:
            return ErlangSpawn.spawn_monitor_3(params.head().toAtom(),
                params.tail().head().toAtom(), params.tail().tail().head().toList());
        default:
            throw Error.badarg;
        }
    }

}
