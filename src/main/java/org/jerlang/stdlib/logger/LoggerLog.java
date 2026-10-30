package org.jerlang.stdlib.logger;

import org.jerlang.erts.erlang.Error;
import org.jerlang.stdlib.Maps;
import org.jerlang.stdlib.Logger;
import org.jerlang.type.List;
import org.jerlang.type.Term;

public class LoggerLog {

    private LoggerLog() {
    }

    public static Term dispatch(List params) {
        switch (params.length()) {
        case 2:
            return Logger.log(params.head(), params.tail().head());
        case 3:
            return Logger.log(params.head(), params.tail().head(),
                params.tail().tail().head());
        default:
            throw Error.badarg;
        }
    }

}
