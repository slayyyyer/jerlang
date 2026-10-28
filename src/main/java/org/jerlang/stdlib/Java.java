package org.jerlang.stdlib;

import org.jerlang.stdlib.java.JavaCall;
import org.jerlang.stdlib.java.JavaCallStatic;
import org.jerlang.stdlib.java.JavaGet;
import org.jerlang.stdlib.java.JavaGetStatic;
import org.jerlang.stdlib.java.JavaNew;
import org.jerlang.stdlib.java.JavaRelease;
import org.jerlang.stdlib.java.JavaSet;
import org.jerlang.stdlib.java.JavaSetStatic;
import org.jerlang.type.List;
import org.jerlang.type.Term;

/**
 * = Java interoperability
 *
 * Calls Java code directly, because the Erlang runtime itself runs on the JVM.
 *
 * Java objects are returned as references and stay alive until released:
 *
 * ----
 * Builder = java:new("java.lang.StringBuilder", []),
 * java:call(Builder, "append", ["hello"]),
 * "hello" = java:call(Builder, "toString", []),
 * java:release(Builder).
 * ----
 *
 * Terms are converted on the way in and out:
 *
 * - Erlang integer -> int/long/short/byte/double/float/BigInteger/BigDecimal
 * - Erlang float -> double/float/BigDecimal
 * - Erlang string or atom -> String
 * - Erlang binary -> byte[]
 * - Erlang list -> java.util.Collection or an array
 * - Erlang tuple -> Object[]
 * - atom true/false -> boolean
 * - atom undefined <- null
 *
 * A Java exception is raised as
 * `error({java_exception, ClassName, Message})`.
 *
 * http://www.erlang.org/doc/man/java.html
 */
public class Java {

    public static Term _new(Term className, Term arguments) {
        return JavaNew.dispatch(List.of(className, arguments));
    }

    public static Term call(Term object, Term method, Term arguments) {
        return JavaCall.dispatch(List.of(object, method, arguments));
    }

    public static Term call_static(Term className, Term method, Term arguments) {
        return JavaCallStatic.dispatch(List.of(className, method, arguments));
    }

    public static Term get(Term object, Term field) {
        return JavaGet.dispatch(List.of(object, field));
    }

    public static Term get_static(Term className, Term field) {
        return JavaGetStatic.dispatch(List.of(className, field));
    }

    public static Term set(Term object, Term field, Term value) {
        return JavaSet.dispatch(List.of(object, field, value));
    }

    public static Term set_static(Term className, Term field, Term value) {
        return JavaSetStatic.dispatch(List.of(className, field, value));
    }

    public static Term release(Term object) {
        return JavaRelease.dispatch(List.of(object));
    }

}
