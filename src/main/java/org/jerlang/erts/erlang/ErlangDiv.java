package org.jerlang.erts.erlang;

import org.jerlang.type.Atom;
import org.jerlang.type.Integer;
import org.jerlang.type.List;
import org.jerlang.type.Term;

/**
 * Integer division of X by Y.
 *
 * OTP truncates toward zero and raises badarith for a zero divisor. BigInteger
 * division has the same truncation rule for the operands Erlang allows here.
 */
public class ErlangDiv {

    private ErlangDiv() {
    }

    public static Term dispatch(List params) {
        switch (params.length()) {
        case 2:
            Integer a = params.head().toInteger();
            params = params.tail();
            Integer b = params.head().toInteger();
            return div_2(a, b);
        default:
            throw Error.badarg;
        }
    }

    public static Integer div_2(Integer a, Integer b) {
        if (b.toBigInteger().signum() == 0) {
            throw new Error(Atom.of("badarith"));
        }
        return new Integer(a.toBigInteger().divide(b.toBigInteger()));
    }

}
