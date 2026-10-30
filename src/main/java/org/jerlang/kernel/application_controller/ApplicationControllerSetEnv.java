package org.jerlang.kernel.application_controller;

import org.jerlang.type.List;
import org.jerlang.type.Term;

/**
 * application_controller:set_env/2,3,4
 */
public class ApplicationControllerSetEnv {

    public static Term dispatch(List params) {
        switch (params.length()) {
        case 2:
            return Applications.setEnv(params.head(), params.tail().head());
        case 3:
            return Applications.setEnv(params.head(), params.tail().head(),
                params.tail().tail().head());
        case 4:
            return Applications.setEnv(params.head(), params.tail().head(),
                params.tail().tail().head(), params.tail().tail().tail().head());
        default:
            throw org.jerlang.erts.erlang.Error.badarg;
        }
    }

}
