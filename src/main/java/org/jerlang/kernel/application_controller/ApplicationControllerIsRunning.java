package org.jerlang.kernel.application_controller;

import org.jerlang.type.List;
import org.jerlang.type.Term;

/**
 * application_controller:is_running/1
 */
public class ApplicationControllerIsRunning {

    public static Term dispatch(List params) {
        return Applications.isRunning(params.head());
    }

}
