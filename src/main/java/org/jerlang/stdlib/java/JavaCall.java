package org.jerlang.stdlib.java;

import org.jerlang.erts.erlang.Error;
import org.jerlang.jvm.JavaBridge;
import org.jerlang.type.List;
import org.jerlang.type.Term;

/**
 * java:call(Object, Method, Args) -> Result
 *
 * Calls an instance method on a Java object.
 *
 * http://www.erlang.org/doc/man/java.html#call-3
 */
public class JavaCall {

    private JavaCall() {
    }

    public static Term dispatch(List params) {
        switch (params.length()) {
        case 3:
            Term object = params.head();
            Term method = params.tail().head();
            Term arguments = params.tail().tail().head();
            return JavaBridge.call(object, method, arguments);
        default:
            throw Error.badarg;
        }
    }

}
