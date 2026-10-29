package org.jerlang.erts.emulator.op;

import static org.junit.Assert.assertEquals;

import org.jerlang.erts.Erlang;
import org.jerlang.exception.ThrowException;
import org.jerlang.type.Atom;
import org.jerlang.type.Integer;
import org.jerlang.type.List;
import org.jerlang.type.Term;
import org.jerlang.type.Tuple;
import org.junit.Test;

/**
 * Runs put_tuple2_y.beam, which is compiled by Erlang/OTP 29 so that the
 * tuple is built straight into a Y register: a call follows it, and every
 * value that stays live crosses that call in the frame.
 */
public class PutTuple2Test extends AbstractOpTest {

    public PutTuple2Test() {
        super("put_tuple2_y.beam");
    }

    @Test
    public void test_put_tuple2_into_a_y_register() throws ThrowException {
        Term result = Erlang.apply(Atom.of("put_tuple2_y"), Atom.of("run"),
            List.of(Integer.of(1)));

        assertEquals(Tuple.of(
            Tuple.of(Atom.of("tuple"), Integer.of(7)), // T
            Integer.of(10), // S = bump(1)
            Integer.of(2), // A
            Integer.of(3), // B
            Integer.of(4), // C
            Integer.of(5), // D
            Integer.of(6), // E
            Integer.of(7)), // F
            result);
    }

}
