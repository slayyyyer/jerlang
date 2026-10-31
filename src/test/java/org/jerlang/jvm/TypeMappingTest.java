package org.jerlang.jvm;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;

import org.jerlang.api.Bridge;
import org.jerlang.type.Atom;
import org.jerlang.type.Binary;
import org.jerlang.type.Float;
import org.jerlang.type.Integer;
import org.jerlang.type.List;
import org.jerlang.type.Str;
import org.jerlang.type.Term;
import org.jerlang.type.Tuple;
import org.junit.Test;

/**
 * One test per row of the table in docs/java-interop.md.
 */
public class TypeMappingTest {

    @Test
    public void nullMapsToUndefined() {
        assertEquals(Atom.of("undefined"), Bridge.toTerm(null));
        assertNull(Bridge.toJava(Atom.of("undefined")));
    }

    @Test
    public void booleansMapToAtoms() {
        assertEquals(Atom.of("true"), Bridge.toTerm(true));
        assertEquals(Atom.of("false"), Bridge.toTerm(false));
        assertEquals(java.lang.Boolean.TRUE, Bridge.toJava(Atom.of("true")));
        assertEquals(java.lang.Boolean.FALSE, Bridge.toJava(Atom.of("false")));
    }

    @Test
    public void atomMapsToString() {
        assertEquals("ok", Bridge.toJava(Atom.of("ok")));
        assertEquals(Str.of("ok"), Bridge.toTerm("ok"));
    }

    @Test
    public void smallIntegerMapsToJavaInteger() {
        assertEquals(Integer.of(7), Bridge.toTerm(7));
        assertEquals(java.lang.Integer.valueOf(7), Bridge.toJava(Integer.of(7)));
    }

    @Test
    public void wideIntegerMapsToJavaLong() {
        assertEquals(new Integer(3_000_000_000L), Bridge.toTerm(3_000_000_000L));
        assertEquals(java.lang.Long.valueOf(3_000_000_000L),
            Bridge.toJava(new Integer(3_000_000_000L)));
    }

    @Test
    public void hugeIntegerMapsToBigInteger() {
        BigInteger value = BigInteger.ONE.shiftLeft(80);
        assertEquals(new Integer(value), Bridge.toTerm(value));
        assertEquals(value, Bridge.toJava(new Integer(value)));
    }

    @Test
    public void floatMapsToDouble() {
        assertEquals(Float.of(1.5), Bridge.toTerm(1.5));
        assertEquals(java.lang.Double.valueOf(1.5), Bridge.toJava(Float.of(1.5)));
    }

    @Test
    public void characterMapsToSingleCharacterString() {
        assertEquals(Str.of("x"), Bridge.toTerm('x'));
    }

    @Test
    public void stringMapsToString() {
        assertEquals(Str.of("abc"), Bridge.toTerm("abc"));
        assertEquals("abc", Bridge.toJava(Str.of("abc")));
    }

    @Test
    public void binaryMapsToByteArray() {
        byte[] bytes = new byte[] { 1, 2, 3 };
        assertEquals(Binary.of(bytes), Bridge.toTerm(bytes));
        assertArrayEquals(bytes, (byte[]) Bridge.toJava(Binary.of(bytes)));
    }

    @Test
    public void listMapsToJavaList() {
        assertEquals(List.of(Integer.of(1), Integer.of(2)),
            Bridge.toTerm(Arrays.asList(1, 2)));
        assertEquals(Arrays.asList(1, 2),
            Bridge.toJava(List.of(Integer.of(1), Integer.of(2))));
        assertTrue(Bridge.toJava(List.nil) instanceof ArrayList);
    }

    @Test
    public void tupleMapsToObjectArray() {
        assertEquals(Tuple.of(Integer.of(1), Str.of("a")),
            Bridge.toTerm(new Object[] { 1, "a" }));
        Object[] array = (Object[]) Bridge.toJava(Tuple.of(Integer.of(1), Str.of("a")));
        assertEquals(2, array.length);
        assertEquals(1, array[0]);
        assertEquals("a", array[1]);
    }

    @Test
    public void mapMapsToJavaMap() {
        Map<Object, Object> javaMap = new LinkedHashMap<>();
        javaMap.put("a", 1);
        javaMap.put(2, "b");

        Term term = Bridge.toTerm(javaMap);
        assertTrue(term instanceof org.jerlang.type.Map);
        org.jerlang.type.Map erlangMap = (org.jerlang.type.Map) term;
        assertEquals(Integer.of(1), erlangMap.get(Str.of("a")));
        assertEquals(Str.of("b"), erlangMap.get(Integer.of(2)));

        @SuppressWarnings("unchecked")
        Map<Object, Object> back = (Map<Object, Object>) Bridge.toJava(erlangMap);
        assertEquals(1, back.get("a"));
        assertEquals("b", back.get(2));
    }

    @Test
    public void erlangMapKeyValuesAreConverted() {
        org.jerlang.type.Map erlangMap = new org.jerlang.type.Map();
        erlangMap.set(Atom.of("key"), List.of(Integer.of(1), Integer.of(2)));
        Map<?, ?> javaMap = (Map<?, ?>) Bridge.toJava(erlangMap);
        assertEquals(Arrays.asList(1, 2), javaMap.get("key"));
    }

}
