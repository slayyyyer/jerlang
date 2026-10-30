package org.jerlang.kernel.application_controller;

import org.jerlang.type.List;
import org.jerlang.type.Term;

/**
 * application_controller:stop_application/1
 */
public class ApplicationControllerStopApplication {

    public static Term dispatch(List params) {
        return Applications.stop(params.head());
    }

}
