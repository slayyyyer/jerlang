package org.jerlang.erts.emulator.op;

import static org.junit.Assert.assertEquals;

import org.jerlang.erts.Erlang;
import org.jerlang.exception.ThrowException;
import org.jerlang.type.Atom;
import org.jerlang.type.Binary;
import org.jerlang.type.List;
import org.jerlang.type.Term;
import org.junit.Test;

public class BsSkipUtfTest extends AbstractOpTest {

    public BsSkipUtfTest() {
        super("bs_skip_utf.beam");
    }

    @Test
    public void test_bs_skip_utf8() throws ThrowException {
        Binary expected = new Binary(new int[] { 'B' });
        List params = List.of(new Binary(new int[] { 'A', 'B' }));
        Term result = Erlang.apply(Atom.of("bs_skip_utf"), Atom.of("test8"), params);
        assertEquals(expected, result);
    }

    @Test
    public void test_bs_skip_utf16() throws ThrowException {
        Binary expected = new Binary(new int[] { 'B' });
        List params = List.of(new Binary(new int[] { 0, 65, 'B' }));
        Term result = Erlang.apply(Atom.of("bs_skip_utf"), Atom.of("test16"), params);
        assertEquals(expected, result);
    }

    @Test
    public void test_bs_skip_utf32() throws ThrowException {
        Binary expected = new Binary(new int[] { 'B' });
        List params = List.of(new Binary(new int[] { 0, 0, 0, 65, 'B' }));
        Term result = Erlang.apply(Atom.of("bs_skip_utf"), Atom.of("test32"), params);
        assertEquals(expected, result);
    }

    @Test
    public void test_bs_skip_utf_decodes_multibyte_code_points() throws ThrowException {
        Binary expected = new Binary(new int[] { 'B' });
        assertEquals(expected, apply("test8_unicode",
            new Binary(new int[] { 0xE2, 0x82, 0xAC, 'B' })));
        assertEquals(expected, apply("test16_unicode",
            new Binary(new int[] { 0xD8, 0x3D, 0xDE, 0x00, 'B' })));
        assertEquals(expected, apply("test32_unicode",
            new Binary(new int[] { 0, 1, 0xF6, 0x00, 'B' })));
    }

    @Test
    public void test_bs_skip_utf_rejects_malformed_input() throws ThrowException {
        assertEquals(Atom.of("no_match"), apply("test8_bad",
            new Binary(new int[] { 0xE2, 0x82 })));
        assertEquals(Atom.of("no_match"), apply("test16_bad",
            new Binary(new int[] { 0xD8, 0x3D })));
        assertEquals(Atom.of("no_match"), apply("test32_bad",
            new Binary(new int[] { 0, 0, 0xD8, 0 })));
    }

    private Term apply(String function, Binary binary) throws ThrowException {
        return Erlang.apply(Atom.of("bs_skip_utf"), Atom.of(function),
            List.of(binary));
    }

}
