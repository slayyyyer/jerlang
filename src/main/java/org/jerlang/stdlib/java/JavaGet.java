package org.jerlang.stdlib.java;

import org.jerlang.erts.erlang.Error;
import org.jerlang.jvm.JavaBridge;
import org.jerlang.type.List;
import org.jerlang.type.Term;

/**
 * java:get(Object, Field) -> Value
 *
 * Reads a public instance field of a Java object.
 *
 * http://www.erlang.org/doc/man/java.html#get-2
 */
public class JavaGet {

    private JavaGet() {
    }

    public static Term dispatch(List params) {
        switch (params.length()) {
        case 2:
            Term object = params.head();
            Term field = params.tail().head();
            return JavaBridge.get(object, field);
        default:
            throw Error.badarg;
        }
    }

}
