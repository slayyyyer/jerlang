package org.jerlang.erts.erlang;

import static org.jerlang.erts.erlang.Error.badarg;

import org.jerlang.FunctionSignature;
import org.jerlang.type.Fun;
import org.jerlang.type.Integer;
import org.jerlang.type.List;
import org.jerlang.type.Term;
import org.jerlang.type.Tuple;

public class ErlangFunInfoMfa {

    private ErlangFunInfoMfa() {
    }

    public static Term dispatch(List params) {
        if (params.length() != 1) {
            throw badarg;
        }
        return fun_info_mfa_1(params.head());
    }

    /**
     * Returns `{Module, Function, Arity}` for a fun: the module the fun was
     * defined in, the name the compiler gave the generated function, and the
     * number of arguments it takes.
     *
     * http://www.erlang.org/doc/man/erlang.html#fun_info_mfa-1
     */
    public static Tuple fun_info_mfa_1(Term fun) {
        if (!(fun instanceof Fun)) {
            throw badarg;
        }
        Fun f = (Fun) fun;
        FunctionSignature signature = f.signature();
        return Tuple.of(signature.module(), signature.function(),
            Integer.of(f.arity()));
    }

}
