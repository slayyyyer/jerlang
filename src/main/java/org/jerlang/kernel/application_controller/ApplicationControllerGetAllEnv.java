package org.jerlang.kernel.application_controller;

import org.jerlang.type.List;
import org.jerlang.type.Term;

/**
 * application_controller:get_all_env/1
 */
public class ApplicationControllerGetAllEnv {

    public static Term dispatch(List params) {
        return Applications.getAllEnv(params.head());
    }

}
