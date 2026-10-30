package org.jerlang.kernel.application_controller;

import org.jerlang.type.List;
import org.jerlang.type.Term;

/**
 * application_controller:get_application_module/1
 */
public class ApplicationControllerGetApplicationModule {

    public static Term dispatch(List params) {
        return Applications.getApplicationModule(params.head());
    }

}
