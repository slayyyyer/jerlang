package org.jerlang.erts.emulator.op;

import static org.junit.Assert.assertEquals;

import org.jerlang.erts.Erlang;
import org.jerlang.exception.ThrowException;
import org.jerlang.type.Atom;
import org.jerlang.type.Binary;
import org.jerlang.type.Integer;
import org.jerlang.type.List;
import org.jerlang.type.Term;
import org.junit.Test;

public class BsGetUtfTest extends AbstractOpTest {

    public BsGetUtfTest() {
        super("bs_get_utf.beam");
    }

    @Test
    public void test_bs_get_utf8() throws ThrowException {
        Integer expected = Integer.of('A');
        List params = List.of(new Binary(new int[] { 'A', 'B' }));
        Term result = Erlang.apply(Atom.of("bs_get_utf"), Atom.of("test8"), params);
        assertEquals(expected, result);
    }

    @Test
    public void test_bs_get_utf16() throws ThrowException {
        Integer expected = Integer.of('A');
        List params = List.of(new Binary(new int[] { 0, 65, 'B' }));
        Term result = Erlang.apply(Atom.of("bs_get_utf"), Atom.of("test16"), params);
        assertEquals(expected, result);
    }

    @Test
    public void test_bs_get_utf32() throws ThrowException {
        Integer expected = Integer.of('A');
        List params = List.of(new Binary(new int[] { 0, 0, 0, 65, 'B' }));
        Term result = Erlang.apply(Atom.of("bs_get_utf"), Atom.of("test32"), params);
        assertEquals(expected, result);
    }

    @Test
    public void test_bs_get_utf8_decodes_a_multibyte_code_point() throws ThrowException {
        assertEquals(Integer.of(0x20AC), apply("test8_unicode",
            new Binary(new int[] { 0xE2, 0x82, 0xAC, 'B' })));
        assertEquals(Integer.of(0x1F600), apply("test8_unicode",
            new Binary(new int[] { 0xF0, 0x9F, 0x98, 0x80, 'B' })));
    }

    @Test
    public void test_bs_get_utf16_decodes_multibyte_and_surrogate_pairs()
        throws ThrowException {
        assertEquals(Integer.of(0x20AC), apply("test16_unicode",
            new Binary(new int[] { 0x20, 0xAC, 'B' })));
        assertEquals(Integer.of(0x1F600), apply("test16_unicode",
            new Binary(new int[] { 0xD8, 0x3D, 0xDE, 0x00, 'B' })));
    }

    @Test
    public void test_bs_get_utf32_decodes_multibyte_code_points() throws ThrowException {
        assertEquals(Integer.of(0x20AC), apply("test32_unicode",
            new Binary(new int[] { 0, 0, 0x20, 0xAC, 'B' })));
        assertEquals(Integer.of(0x1F600), apply("test32_unicode",
            new Binary(new int[] { 0, 1, 0xF6, 0x00, 'B' })));
    }

    @Test
    public void test_bs_get_utf16_and_utf32_honour_little_endian() throws ThrowException {
        assertEquals(Integer.of(0x20AC), apply("test16_little",
            new Binary(new int[] { 0xAC, 0x20, 'B' })));
        assertEquals(Integer.of(0x20AC), apply("test32_little",
            new Binary(new int[] { 0xAC, 0x20, 0, 0, 'B' })));
    }

    @Test
    public void test_bs_get_utf_rejects_malformed_input() throws ThrowException {
        assertEquals(Atom.of("no_match"), apply("test8_bad",
            new Binary(new int[] { 0xE2, 0x82 })));
        assertEquals(Atom.of("no_match"), apply("test8_bad",
            new Binary(new int[] { 0xC0, 0x80 })));
        assertEquals(Atom.of("no_match"), apply("test16_bad",
            new Binary(new int[] { 0xD8, 0x3D })));
        assertEquals(Atom.of("no_match"), apply("test16_bad",
            new Binary(new int[] { 0xDC, 0x00, 'B' })));
        assertEquals(Atom.of("no_match"), apply("test32_bad",
            new Binary(new int[] { 0, 0x11, 0, 0 })));
        assertEquals(Atom.of("no_match"), apply("test32_bad",
            new Binary(new int[] { 0, 0, 0xD8, 0 })));
    }

    private Term apply(String function, Binary binary) throws ThrowException {
        return Erlang.apply(Atom.of("bs_get_utf"), Atom.of(function),
            List.of(binary));
    }

}
