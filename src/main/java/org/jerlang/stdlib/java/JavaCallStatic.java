package org.jerlang.stdlib.java;

import org.jerlang.erts.erlang.Error;
import org.jerlang.jvm.JavaBridge;
import org.jerlang.type.List;
import org.jerlang.type.Term;

/**
 * java:call_static(ClassName, Method, Args) -> Result
 *
 * Calls a static method on a Java class.
 *
 * http://www.erlang.org/doc/man/java.html#call_static-3
 */
public class JavaCallStatic {

    private JavaCallStatic() {
    }

    public static Term dispatch(List params) {
        switch (params.length()) {
        case 3:
            Term className = params.head();
            Term method = params.tail().head();
            Term arguments = params.tail().tail().head();
            return JavaBridge.callStatic(className, method, arguments);
        default:
            throw Error.badarg;
        }
    }

}
