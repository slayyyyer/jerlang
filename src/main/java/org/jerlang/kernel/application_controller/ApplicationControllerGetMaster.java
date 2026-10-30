package org.jerlang.kernel.application_controller;

import org.jerlang.type.List;
import org.jerlang.type.Term;

/**
 * application_controller:get_master/1
 */
public class ApplicationControllerGetMaster {

    public static Term dispatch(List params) {
        return Applications.getMaster(params.head());
    }

}
