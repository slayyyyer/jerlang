package org.jerlang.kernel.application_controller;

import org.jerlang.type.List;
import org.jerlang.type.Term;

/**
 * application_controller:unset_env/2,3
 */
public class ApplicationControllerUnsetEnv {

    public static Term dispatch(List params) {
        Term application = params.head();
        Term key = params.tail().head();
        switch (params.length()) {
        case 2:
            return Applications.unsetEnv(application, key);
        case 3:
            return Applications.unsetEnv(application, key, params.tail().tail().head());
        default:
            throw org.jerlang.erts.erlang.Error.badarg;
        }
    }

}
