package org.jerlang.erts.etf;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.math.BigInteger;
import java.util.zip.DeflaterOutputStream;

import org.jerlang.type.Atom;
import org.jerlang.type.Binary;
import org.jerlang.type.BitString;
import org.jerlang.type.Float;
import org.jerlang.type.ImproperList;
import org.jerlang.type.Integer;
import org.jerlang.type.List;
import org.jerlang.type.Map;
import org.jerlang.type.PID;
import org.jerlang.type.PortID;
import org.jerlang.type.Reference;
import org.jerlang.type.Str;
import org.jerlang.type.Term;
import org.jerlang.type.Tuple;
import org.junit.Test;

/**
 * The expected byte sequences in this test were produced by
 * erlang:term_to_binary/1 on Erlang/OTP 29, so they also prove that the
 * encoding is understood by a real Erlang node.
 */
public class ExternalTermFormatTest {

    private static Term roundtrip(Term term) {
        return decode(ExternalTermWriter.encode(term));
    }

    private static Term decode(Term binary) {
        try {
            return new ExternalTermReader(((Binary) binary).toByteArray()).read();
        } catch (Throwable e) {
            throw new AssertionError(e);
        }
    }

    @Test
    public void encodesTermsLikeOtp() {
        assertArrayEquals(bytes(131, 104, 2, 119, 1, 97, 97, 1),
            ExternalTermWriter.encode(Tuple.of(Atom.of("a"), Integer.of(1))).toByteArray());
        assertArrayEquals(bytes(131, 98, 0, 0, 3, 232),
            ExternalTermWriter.encode(Integer.of(1000)).toByteArray());
        assertArrayEquals(bytes(131, 98, 255, 255, 255, 255),
            ExternalTermWriter.encode(Integer.of(-1)).toByteArray());
        assertArrayEquals(bytes(131, 109, 0, 0, 0, 3, 1, 2, 3),
            ExternalTermWriter.encode(Binary.of(1, 2, 3)).toByteArray());
        assertArrayEquals(bytes(131, 70, 64, 12, 0, 0, 0, 0, 0, 0),
            ExternalTermWriter.encode(Float.of(3.5)).toByteArray());
        assertArrayEquals(bytes(131, 116, 0, 0, 0, 1, 119, 1, 97, 97, 1),
            ExternalTermWriter.encode(map(Atom.of("a"), Integer.of(1))).toByteArray());
    }

    @Test
    public void roundtripsEverySupportedTermType() {
        assertEquals(Atom.of("hello"), roundtrip(Atom.of("hello")));
        assertEquals(Atom.of("héllo"), roundtrip(Atom.of("héllo")));
        assertEquals(Integer.of(0), roundtrip(Integer.of(0)));
        assertEquals(Integer.of(255), roundtrip(Integer.of(255)));
        assertEquals(Integer.of(256), roundtrip(Integer.of(256)));
        assertEquals(Integer.of(-1), roundtrip(Integer.of(-1)));
        assertEquals(Integer.of(java.lang.Integer.MIN_VALUE),
            roundtrip(Integer.of(java.lang.Integer.MIN_VALUE)));
        assertEquals(Integer.of(java.lang.Integer.MAX_VALUE),
            roundtrip(Integer.of(java.lang.Integer.MAX_VALUE)));
        BigInteger big = BigInteger.valueOf(2).pow(70).negate();
        assertEquals(new Integer(big), roundtrip(new Integer(big)));
        assertEquals(Float.of(3.5), roundtrip(Float.of(3.5)));
        assertEquals(Binary.of(1, 2, 3), roundtrip(Binary.of(1, 2, 3)));
        assertEquals(List.nil, roundtrip(List.nil));
        assertEquals(Str.of("abc"), roundtrip(Str.of("abc")));
        assertEquals(List.of(Integer.of(1), Atom.of("two"), Binary.of(3)),
            roundtrip(List.of(Integer.of(1), Atom.of("two"), Binary.of(3))));
        assertEquals(Tuple.of(Atom.of("a"), Integer.of(1), List.nil),
            roundtrip(Tuple.of(Atom.of("a"), Integer.of(1), List.nil)));
        assertEquals(map(Atom.of("a"), Integer.of(1), Atom.of("b"), Integer.of(2)),
            roundtrip(map(Atom.of("a"), Integer.of(1), Atom.of("b"), Integer.of(2))));
    }

    @Test
    public void roundtripsBitstringsWithUnusedBits() {
        BitString bitString = new BitString(new byte[] { (byte) 0b10100000 }, 5);
        BitString decoded = roundtrip(bitString).toBitString();
        assertEquals(3, decoded.bits());
        assertEquals(bitString, decoded);
    }

    @Test
    public void roundtripsLargeTuples() {
        Term[] elements = new Term[300];
        for (int index = 0; index < elements.length; index++) {
            elements[index] = Integer.of(index);
        }
        Tuple tuple = Tuple.of(elements);
        assertEquals(tuple, roundtrip(tuple));
    }

    @Test
    public void roundtripsLocalAndRemotePids() {
        PID local = new PID(42);
        PID decodedLocal = roundtrip(local).toPID();
        assertEquals(local, decodedLocal);
        assertEquals("<0.42.0>", decodedLocal.toString());

        PID remote = new PID(Atom.of("peer@host"), 7, 3, 300);
        assertEquals(remote, roundtrip(remote));
    }

    @Test
    public void encodesALocalPidExactlyLikeOtp() {
        // erlang:term_to_binary(self()), OTP 29 on an unnamed node.
        int[] otp = pidBytesFromOtp();
        assertArrayEquals(bytes(otp),
            ExternalTermWriter.encode(new PID(10)).toByteArray());
    }

    @Test
    public void decodesPidsWrittenByOtp() {
        PID decoded = decode(new Binary(pidBytesFromOtp())).toPID();
        assertEquals(new PID(Atom.of("nonode@nohost"), 10, 0, 0), decoded);
    }

    @Test
    public void decodesReferencesWrittenByOtp() {
        // erlang:term_to_binary(make_ref()), OTP 29, NEWER_REFERENCE_EXT.
        int[] otp = referenceBytesFromOtp();
        Reference reference = decode(new Binary(otp)).toReference();
        assertEquals(Atom.of("nonode@nohost"), reference.node());
        assertEquals(0, reference.creation());
        assertEquals(new BigInteger("3c996103800037bb06073", 16), reference.id());
    }

    @Test
    public void roundtripsReferences() {
        Reference local = new Reference();
        assertEquals(local, roundtrip(local));

        Reference remote = new Reference(Atom.of("peer@host"),
            new BigInteger("123456789012345678901"), 2);
        assertEquals(remote, roundtrip(remote));
    }

    @Test
    public void roundtripsFiveWordReferences() {
        // OTP 26 and later send up to five 32 bit words for a reference.
        Reference reference = new Reference(Atom.of("peer@host"),
            new BigInteger("123456789012345678901234567890123456789"), 3);
        assertEquals(reference, roundtrip(reference));
    }

    @Test
    public void roundtripsImproperLists() {
        // net_kernel uses [alias | Reference] as a gen_server reply tag.
        Reference reference = new Reference(Atom.of("peer@host"),
            new BigInteger("98765432109876543210987654321"), 4);
        ImproperList improper = new ImproperList(Atom.of("alias"), reference);
        assertEquals(improper, roundtrip(improper));
    }

    @Test
    public void roundtripsUnicodeAtomsAndStrings() {
        assertEquals(Atom.of("ταυρος"), roundtrip(Atom.of("ταυρος")));
        assertEquals(Str.of("héllo"), roundtrip(Str.of("héllo")));
    }

    @Test
    public void decodesCompressedTerms() throws Exception {
        Tuple tuple = Tuple.of(Atom.of("compressed"), Integer.of(42));
        byte[] encoded = ExternalTermWriter.encode(tuple).toByteArray();
        ByteArrayOutputStream compressed = new ByteArrayOutputStream();
        DeflaterOutputStream deflater = new DeflaterOutputStream(compressed);
        deflater.write(encoded);
        deflater.close();

        ByteArrayOutputStream packet = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(packet);
        out.writeByte(80);
        out.writeInt(encoded.length);
        out.write(compressed.toByteArray());
        out.flush();

        assertEquals(tuple, decode(new Binary(packet.toByteArray())));
    }

    @Test
    public void encodesALocalPortExactlyLikeOtp() {
        // erlang:term_to_binary(open_port({spawn, "cmd"}, [])), OTP 29.
        assertArrayEquals(bytes(portBytesFromOtp()),
            ExternalTermWriter.encode(new PortID(1)).toByteArray());
    }

    @Test
    public void decodesPortsWrittenByOtp() {
        PortID decoded = decode(new Binary(portBytesFromOtp())).toPortID();
        assertEquals(new PortID(Atom.of("nonode@nohost"), 1, 0), decoded);
        assertEquals("#Port<0.1>", decoded.toString());
    }

    @Test
    public void decodesLegacyPortsWithAOneByteCreation() {
        // PORT_EXT, the form OTP used before 26: 102, node, id, creation.
        int[] legacy = new int[] { 131, 102, 119, 13, 110, 111, 110, 111, 100,
            101, 64, 110, 111, 104, 111, 115, 116, 0, 0, 0, 7, 3 };
        assertEquals(new PortID(Atom.of("nonode@nohost"), 7, 3),
            decode(new Binary(bytes(legacy))).toPortID());
    }

    @Test
    public void roundtripsLocalAndRemotePorts() {
        PortID local = new PortID(42);
        assertEquals(local, roundtrip(local));

        PortID remote = new PortID(Atom.of("peer@host"), 9, 300);
        PortID decoded = roundtrip(remote).toPortID();
        assertEquals(remote, decoded);
        assertEquals("#Port<peer@host.9>", decoded.toString());
    }

    @Test
    public void roundtripsPortsInsideTuples() {
        Tuple message = Tuple.of(Atom.of("port"), new PortID(3));
        assertEquals(message, roundtrip(message));
    }

    private static int[] pidBytesFromOtp() {
        return new int[] { 131, 88, 119, 13, 110, 111, 110, 111, 100, 101, 64,
            110, 111, 104, 111, 115, 116, 0, 0, 0, 10, 0, 0, 0, 0, 0, 0, 0, 0 };
    }

    private static int[] portBytesFromOtp() {
        return new int[] { 131, 120, 119, 13, 110, 111, 110, 111, 100, 101, 64,
            110, 111, 104, 111, 115, 116, 0, 0, 0, 0, 0, 0, 0, 1, 0, 0, 0, 0 };
    }

    private static int[] referenceBytesFromOtp() {
        return new int[] { 131, 90, 0, 3, 119, 13, 110, 111, 110, 111, 100, 101,
            64, 110, 111, 104, 111, 115, 116, 0, 0, 0, 0, 0, 3, 201, 150, 16,
            56, 0, 3, 123, 176, 96, 115 };
    }

    private static Map map(Term... pairs) {
        Map map = new Map();
        for (int index = 0; index < pairs.length; index += 2) {
            map.set(pairs[index], pairs[index + 1]);
        }
        return map;
    }

    private static byte[] bytes(int... values) {
        byte[] bytes = new byte[values.length];
        for (int index = 0; index < values.length; index++) {
            bytes[index] = (byte) values[index];
        }
        return bytes;
    }

}
