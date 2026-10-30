package org.jerlang.kernel.application_controller;

import org.jerlang.type.List;
import org.jerlang.type.Term;

/**
 * application_controller:permit_application/2
 */
public class ApplicationControllerPermitApplication {

    public static Term dispatch(List params) {
        return Applications.permitApplication(params.head(), params.tail().head());
    }

}
