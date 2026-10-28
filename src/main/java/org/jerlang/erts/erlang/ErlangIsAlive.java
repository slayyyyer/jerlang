package org.jerlang.erts.erlang;

import org.jerlang.erts.dist.DistNodeName;
import org.jerlang.type.Boolean;
import org.jerlang.type.List;
import org.jerlang.type.Term;
import org.jerlang.vm.VirtualMachine;

/**
 * True when this node takes part in distribution.
 *
 * http://www.erlang.org/doc/man/erlang.html#is_alive-0
 */
public class ErlangIsAlive {

    private ErlangIsAlive() {
    }

    public static Term dispatch(List params) {
        if (params.length() != 0) {
            throw Error.badarg;
        }
        return is_alive_0();
    }

    public static Term is_alive_0() {
        return Boolean.of(DistNodeName.isValid(VirtualMachine.instance().node()));
    }

}
