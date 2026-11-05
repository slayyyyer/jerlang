package org.jerlang.erts.emulator.op;

import static org.junit.Assert.assertEquals;

import org.jerlang.erts.Erlang;
import org.jerlang.exception.ThrowException;
import org.jerlang.type.Atom;
import org.jerlang.type.Integer;
import org.jerlang.type.List;
import org.jerlang.type.Map;
import org.jerlang.type.Term;
import org.junit.Test;

public class PutMapYTest extends AbstractOpTest {

    private static final Atom a = Atom.of("a");
    private static final Atom b = Atom.of("b");
    private static final Atom put_map_y = Atom.of("put_map_y");
    private static final Atom test = Atom.of("test");

    public PutMapYTest() {
        super("put_map_y.beam");
    }

    @Test
    public void test_put_map_assoc_into_y_register() throws ThrowException {
        Map expected = new Map();
        expected.set(a, Integer.of(1));
        expected.set(b, Integer.of(2));
        Term result = Erlang.apply(put_map_y, test, List.of(new Map()));
        assertEquals(expected, result);
    }

}
