package org.jerlang.kernel.application_controller;

import org.jerlang.type.List;
import org.jerlang.type.Term;

/**
 * application_controller:loaded_applications/0
 */
public class ApplicationControllerLoadedApplications {

    public static Term dispatch(List params) {
        return Applications.loadedApplications();
    }

}
