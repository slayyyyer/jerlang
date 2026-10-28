package org.jerlang.kernel.net_kernel;

import org.jerlang.erts.erlang.Error;
import org.jerlang.kernel.NetKernel;
import org.jerlang.type.List;
import org.jerlang.type.Term;

public class NetKernelDisconnectNode {

    private NetKernelDisconnectNode() {
    }

    public static Term dispatch(List params) {
        if (params.length() != 1) {
            throw Error.badarg;
        }
        return NetKernel.disconnect_node(params.head());
    }

}
