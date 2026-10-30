package org.jerlang.kernel.application_controller;

import org.jerlang.type.List;
import org.jerlang.type.Term;

/**
 * application_controller:load_application/1
 */
public class ApplicationControllerLoadApplication {

    public static Term dispatch(List params) {
        return Applications.load(params.head());
    }

}
