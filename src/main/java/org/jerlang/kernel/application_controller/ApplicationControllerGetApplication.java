package org.jerlang.kernel.application_controller;

import org.jerlang.type.List;
import org.jerlang.type.Term;

/**
 * application_controller:get_application/1
 */
public class ApplicationControllerGetApplication {

    public static Term dispatch(List params) {
        return Applications.getApplication(params.head());
    }

}
