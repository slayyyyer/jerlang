package org.jerlang.stdlib.logger;

import org.jerlang.erts.erlang.Error;
import org.jerlang.stdlib.Logger;
import org.jerlang.type.List;
import org.jerlang.type.Term;

public class LoggerCompareLevels {

    private LoggerCompareLevels() {
    }

    public static Term dispatch(List params) {
        switch (params.length()) {
        case 2:
            return Logger.compare_levels(params.head(), params.tail().head());
        default:
            throw Error.badarg;
        }
    }

}
