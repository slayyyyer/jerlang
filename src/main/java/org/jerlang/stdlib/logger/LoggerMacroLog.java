package org.jerlang.stdlib.logger;

import org.jerlang.erts.erlang.Error;
import org.jerlang.stdlib.Logger;
import org.jerlang.type.List;
import org.jerlang.type.Term;

public class LoggerMacroLog {

    private LoggerMacroLog() {
    }

    public static Term dispatch(List params) {
        switch (params.length()) {
        case 4:
            return Logger.macro_log(params.head(), params.tail().head(),
                params.tail().tail().head(), params.tail().tail().tail().head());
        default:
            throw Error.badarg;
        }
    }

}
