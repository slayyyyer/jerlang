package org.jerlang.kernel.application_controller;

import org.jerlang.type.List;
import org.jerlang.type.Term;

/**
 * application_controller:get_key/2
 */
public class ApplicationControllerGetKey {

    public static Term dispatch(List params) {
        return Applications.getKey(params.head(), params.tail().head());
    }

}
