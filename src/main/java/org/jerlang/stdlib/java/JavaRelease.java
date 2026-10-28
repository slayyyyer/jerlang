package org.jerlang.stdlib.java;

import org.jerlang.erts.erlang.Error;
import org.jerlang.jvm.JavaBridge;
import org.jerlang.type.List;
import org.jerlang.type.Term;

/**
 * java:release(Object) -> true
 *
 * Drops the bridge's reference to a Java object.
 * The JVM garbage collector may reclaim it afterwards.
 *
 * http://www.erlang.org/doc/man/java.html#release-1
 */
public class JavaRelease {

    private JavaRelease() {
    }

    public static Term dispatch(List params) {
        switch (params.length()) {
        case 1:
            return JavaBridge.release(params.head());
        default:
            throw Error.badarg;
        }
    }

}
