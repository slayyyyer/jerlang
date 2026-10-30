package org.jerlang.erts.erlang;

import org.jerlang.type.Atom;
import org.jerlang.type.List;
import org.jerlang.type.Map;
import org.jerlang.type.Term;
import org.jerlang.type.Tuple;

public class ErlangError {

    private static final Atom ERROR_INFO = Atom.of("error_info");

    private ErlangError() {
    }

    public static Term dispatch(List params) {
        switch (params.length()) {
        case 1:
            error_1(params.head());
            return null;
        case 2:
            Term reason = params.head();
            params = params.tail();
            error_2(reason, params.head());
            return null;
        case 3:
            Term failed = params.head();
            params = params.tail();
            Term args = params.head();
            error_3(failed, args, params.tail().head());
            return null;
        default:
            throw Error.badarg;
        }
    }

    /**
     * Stops the execution of the calling process with the reason `reason`,
     * where `reason` is any term. The actual exit reason will be
     * `{reason, where}`, where `where` is a list of the functions most
     * recently called (the current function first). Since evaluating this
     * function causes the process to terminate, it has no return value.
     *
     * http://www.erlang.org/doc/man/erlang.html#error-1
     */
    public static void error_1(Term reason) {
        throw new Error(reason);
    }

    /**
     * Stops the execution of the calling process with the reason `reason`,
     * where `reason` is any term. The actual exit reason will be
     * `{reason, where}`, where `where` is a list of the functions most
     * recently called (the current function first). `args` is expected to
     * be the list of arguments for the current function; in Beam it will be
     * used to provide the actual arguments for the current function in the
     * `where` term. Since evaluating this function causes the process to
     * terminate, it has no return value.
     *
     * http://www.erlang.org/doc/man/erlang.html#error-2
     */
    public static void error_2(Term reason, Term args) {
        throw new Error(reason, list(args));
    }

    /**
     * Like error/2, but takes an options list. The only option is
     * `{error_info, #{...}}`, a map that is attached to the exception for the
     * crash report instead of being a part of the reason.
     *
     * http://www.erlang.org/doc/man/erlang.html#error-3
     */
    public static void error_3(Term reason, Term args, Term options) {
        throw new Error(reason, list(args), errorInfo(list(options)));
    }

    /**
     * The argument list and the options of error/2,3 have to be lists; anything
     * else is a badarg, as in OTP.
     */
    private static List list(Term term) {
        if (!term.isList()) {
            throw Error.badarg;
        }
        return term.toList();
    }

    /**
     * The map of the `error_info` option, or null when the options list is
     * empty. Anything else is a badarg, as in OTP.
     */
    private static Term errorInfo(List options) {
        Term info = null;
        while (options.length() > 0) {
            Term option = options.head();
            if (!(option instanceof Tuple)) {
                throw Error.badarg;
            }
            Tuple tuple = (Tuple) option;
            if (tuple.arity() != 2 || !ERROR_INFO.equals(tuple.element(1))
                || !(tuple.element(2) instanceof Map)) {
                throw Error.badarg;
            }
            info = tuple.element(2);
            options = options.tail();
        }
        return info;
    }

}
