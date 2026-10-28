package org.jerlang.stdlib.java;

import org.jerlang.erts.erlang.Error;
import org.jerlang.jvm.JavaBridge;
import org.jerlang.type.List;
import org.jerlang.type.Term;

/**
 * java:set(Object, Field, Value) -> true
 *
 * Writes a public instance field of a Java object.
 *
 * http://www.erlang.org/doc/man/java.html#set-3
 */
public class JavaSet {

    private JavaSet() {
    }

    public static Term dispatch(List params) {
        switch (params.length()) {
        case 3:
            Term object = params.head();
            Term field = params.tail().head();
            Term value = params.tail().tail().head();
            return JavaBridge.set(object, field, value);
        default:
            throw Error.badarg;
        }
    }

}
