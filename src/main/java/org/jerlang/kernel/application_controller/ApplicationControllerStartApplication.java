package org.jerlang.kernel.application_controller;

import org.jerlang.type.List;
import org.jerlang.type.Term;

/**
 * application_controller:start_application/2
 */
public class ApplicationControllerStartApplication {

    public static Term dispatch(List params) {
        Term application = params.head();
        Term type = params.tail().head();
        return Applications.start(application, type);
    }

}
