package org.jerlang.kernel.application_controller;

import org.jerlang.type.List;
import org.jerlang.type.Term;

/**
 * application_controller:start_type/1
 */
public class ApplicationControllerStartType {

    public static Term dispatch(List params) {
        return Applications.startType(params.head());
    }

}
