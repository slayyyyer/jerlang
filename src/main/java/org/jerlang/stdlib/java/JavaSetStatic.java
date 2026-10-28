package org.jerlang.stdlib.java;

import org.jerlang.erts.erlang.Error;
import org.jerlang.jvm.JavaBridge;
import org.jerlang.type.List;
import org.jerlang.type.Term;

/**
 * java:set_static(ClassName, Field, Value) -> true
 *
 * Writes a public static field of a Java class.
 *
 * http://www.erlang.org/doc/man/java.html#set_static-3
 */
public class JavaSetStatic {

    private JavaSetStatic() {
    }

    public static Term dispatch(List params) {
        switch (params.length()) {
        case 3:
            Term className = params.head();
            Term field = params.tail().head();
            Term value = params.tail().tail().head();
            return JavaBridge.setStatic(className, field, value);
        default:
            throw Error.badarg;
        }
    }

}
