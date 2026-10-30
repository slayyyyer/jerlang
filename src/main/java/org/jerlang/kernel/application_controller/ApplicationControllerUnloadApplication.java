package org.jerlang.kernel.application_controller;

import org.jerlang.type.List;
import org.jerlang.type.Term;

/**
 * application_controller:unload_application/1
 */
public class ApplicationControllerUnloadApplication {

    public static Term dispatch(List params) {
        return Applications.unload(params.head());
    }

}
