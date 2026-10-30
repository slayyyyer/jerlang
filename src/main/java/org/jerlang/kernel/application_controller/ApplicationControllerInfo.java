package org.jerlang.kernel.application_controller;

import org.jerlang.type.List;
import org.jerlang.type.Term;

/**
 * application_controller:info/0
 */
public class ApplicationControllerInfo {

    public static Term dispatch(List params) {
        return Applications.info();
    }

}
