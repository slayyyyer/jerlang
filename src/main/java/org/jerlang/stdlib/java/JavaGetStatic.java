package org.jerlang.stdlib.java;

import org.jerlang.erts.erlang.Error;
import org.jerlang.jvm.JavaBridge;
import org.jerlang.type.List;
import org.jerlang.type.Term;

/**
 * java:get_static(ClassName, Field) -> Value
 *
 * Reads a public static field of a Java class.
 *
 * http://www.erlang.org/doc/man/java.html#get_static-2
 */
public class JavaGetStatic {

    private JavaGetStatic() {
    }

    public static Term dispatch(List params) {
        switch (params.length()) {
        case 2:
            Term className = params.head();
            Term field = params.tail().head();
            return JavaBridge.getStatic(className, field);
        default:
            throw Error.badarg;
        }
    }

}
