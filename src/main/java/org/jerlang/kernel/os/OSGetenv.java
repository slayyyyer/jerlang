package org.jerlang.kernel.os;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.jerlang.erts.erlang.Error;
import org.jerlang.type.Atom;
import org.jerlang.type.List;
import org.jerlang.type.Str;
import org.jerlang.type.Term;

public class OSGetenv {

    /**
     * Values set by os:putenv/2.
     *
     * The JVM snapshots the process environment on startup, so a native
     * setenv(3) call is not visible to System.getenv(). This overlay keeps
     * os:getenv/0,1,2 consistent with what os:putenv/2 was told, while the
     * native call still updates the environment inherited by child processes.
     */
    private static final Map<String, String> overlay = new ConcurrentHashMap<>();

    public static Term dispatch(List params) {
        switch (params.length()) {
        case 0:
            return getenv_0();
        case 1:
            return getenv_1(params.head());
        case 2:
            Term varName = params.head();
            params = params.tail();
            Term defaultValue = params.head();
            return getenv_2(varName, defaultValue);
        default:
            throw Error.badarg;
        }
    }

    /**
     * Returns a list of all environment variables.
     * Each environment variable is given as a single string on the format
     * "VarName=Value",
     * where VarName is the name of the variable and Value its value.
     *
     * http://www.erlang.org/doc/man/os.html#getenv-0
     */
    public static List getenv_0() {
        Map<String, String> environment = new LinkedHashMap<>(System.getenv());
        environment.putAll(overlay);
        List result = List.nil;
        for (Map.Entry<String, String> entry : environment.entrySet()) {
            result = new List(Str.of(entry.getKey() + "=" + entry.getValue()), result);
        }
        return result;
    }

    /**
     * Returns the Value of the environment variable VarName,
     * or false if the environment variable is undefined.
     *
     * http://www.erlang.org/doc/man/os.html#getenv-1
     */
    public static Term getenv_1(Term varName) {
        return getenv_2(varName, Atom.of("false"));
    }

    /**
     * Returns the Value of the environment variable VarName,
     * or DefaultValue if the environment variable is undefined.
     *
     * http://www.erlang.org/doc/man/os.html#getenv-2
     */
    public static Term getenv_2(Term varName, Term defaultValue) {
        String result = overlay.get(((Str) varName).string());
        if (result == null) {
            result = System.getenv(((Str) varName).string());
        }
        if (result == null) {
            return defaultValue;
        } else {
            return Str.of(result);
        }
    }

    /**
     * Records a value set by os:putenv/2, so that os:getenv/0,1,2 observe it.
     */
    public static void set(String varName, String value) {
        overlay.put(varName, value);
    }

}
