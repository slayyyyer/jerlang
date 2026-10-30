package org.jerlang.erts.erlang;

import org.jerlang.FunctionSignature;
import org.jerlang.type.Atom;
import org.jerlang.type.Fun;
import org.jerlang.type.Integer;
import org.jerlang.type.List;
import org.jerlang.type.Term;

/**
 * erlang:make_fun/3 builds a `fun M:F/A` for a module that is only known at
 * runtime. The function does not have to exist or be exported: the fun is an
 * external fun, and applying it applies M:F/A, which raises undef if the
 * function is missing. gen_server builds the optional callbacks that way, so
 * a callback module that does not implement handle_continue/2 still starts.
 *
 * http://www.erlang.org/doc/man/erlang.html#make_fun-3
 */
public class ErlangMakeFun {

    private ErlangMakeFun() {
    }

    public static Term dispatch(List params) {
        switch (params.length()) {
        case 3:
            Atom module = params.head().toAtom();
            params = params.tail();
            Atom function = params.head().toAtom();
            params = params.tail();
            return make_fun_3(module, function, params.head().toInteger());
        default:
            throw Error.badarg;
        }
    }

    public static Fun make_fun_3(Atom module, Atom function, Integer arity) {
        if (arity.toInt() < 0) {
            throw Error.badarg;
        }
        return Fun.external(new FunctionSignature(module, function, arity));
    }

}
