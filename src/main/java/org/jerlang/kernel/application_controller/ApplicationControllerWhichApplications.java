package org.jerlang.kernel.application_controller;

import org.jerlang.type.List;
import org.jerlang.type.Term;

/**
 * application_controller:which_applications/0,1
 */
public class ApplicationControllerWhichApplications {

    public static Term dispatch(List params) {
        return Applications.whichApplications();
    }

}
