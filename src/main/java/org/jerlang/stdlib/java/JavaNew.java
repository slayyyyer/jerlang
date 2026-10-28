package org.jerlang.stdlib.java;

import org.jerlang.erts.erlang.Error;
import org.jerlang.jvm.JavaBridge;
import org.jerlang.type.List;
import org.jerlang.type.Term;

/**
 * java:new(ClassName, Args) -> Object
 *
 * Creates a Java object by calling a constructor that matches Args.
 *
 * http://www.erlang.org/doc/man/java.html#new-2
 */
public class JavaNew {

    private JavaNew() {
    }

    public static Term dispatch(List params) {
        switch (params.length()) {
        case 2:
            Term className = params.head();
            Term arguments = params.tail().head();
            return JavaBridge.newObject(className, arguments);
        default:
            throw Error.badarg;
        }
    }

}
