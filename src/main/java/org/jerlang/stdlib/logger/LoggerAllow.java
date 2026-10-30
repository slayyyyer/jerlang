package org.jerlang.stdlib.logger;

import org.jerlang.erts.erlang.Error;
import org.jerlang.stdlib.Logger;
import org.jerlang.type.List;
import org.jerlang.type.Term;

public class LoggerAllow {

    private LoggerAllow() {
    }

    public static Term dispatch(List params) {
        switch (params.length()) {
        case 2:
            return Logger.allow(params.head(), params.tail().head());
        case 3:
            return Logger.allow(params.head(), params.tail().head(),
                params.tail().tail().head());
        default:
            throw Error.badarg;
        }
    }

}
