package org.jerlang.kernel.application_controller;

import org.jerlang.type.List;
import org.jerlang.type.Term;

/**
 * application_controller:get_all_key/1
 */
public class ApplicationControllerGetAllKey {

    public static Term dispatch(List params) {
        return Applications.getAllKey(params.head());
    }

}
