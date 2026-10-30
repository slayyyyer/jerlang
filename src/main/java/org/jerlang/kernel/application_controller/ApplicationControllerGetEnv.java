package org.jerlang.kernel.application_controller;

import org.jerlang.type.List;
import org.jerlang.type.Term;

/**
 * application_controller:get_env/2,3
 */
public class ApplicationControllerGetEnv {

    public static Term dispatch(List params) {
        Term application = params.head();
        params = params.tail();
        Term key = params.head();
        switch (params.length()) {
        case 1:
            return Applications.getEnv(application, key);
        case 2:
            return Applications.getEnv(application, key, params.tail().head());
        default:
            throw org.jerlang.erts.erlang.Error.badarg;
        }
    }

}
