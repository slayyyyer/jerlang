package org.jerlang.kernel.net_kernel;

import org.jerlang.erts.erlang.Error;
import org.jerlang.kernel.NetKernel;
import org.jerlang.type.Atom;
import org.jerlang.type.List;
import org.jerlang.type.Term;

public class NetKernelMonitorNodes {

    private NetKernelMonitorNodes() {
    }

    public static Term dispatch(List params) {
        switch (params.length()) {
        case 1:
            return NetKernel.monitor_nodes(params.head());
        case 2:
            return NetKernel.monitor_nodes(params.head(), params.tail().head());
        default:
            throw Error.badarg;
        }
    }

}
