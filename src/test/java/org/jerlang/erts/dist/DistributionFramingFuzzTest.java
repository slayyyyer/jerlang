package org.jerlang.erts.dist;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.IOException;
import java.util.Random;

import org.jerlang.type.Atom;
import org.jerlang.type.Binary;
import org.jerlang.type.Integer;
import org.jerlang.type.List;
import org.jerlang.type.PID;
import org.jerlang.type.Term;
import org.jerlang.type.Tuple;
import org.junit.Test;

/**
 * Deterministic framing fuzzing for the 112-tagged distribution format.
 *
 * The reader loop trusts a four byte length and a type byte before it trusts
 * anything else, so those are checked here directly, together with the ETF
 * boundary: a malformed packet has to fail as an IOException rather than
 * escape as a runtime failure or request an unbounded allocation.
 */
public class DistributionFramingFuzzTest {

    private static final Atom PEER = Atom.of("peer@127.0.0.1");
    private static final int ITERATIONS = Math.max(1,
        java.lang.Integer.getInteger("jerlang.fuzz.iterations", 200));

    @Test
    public void validFramesRoundTrip() throws Exception {
        Random random = new Random(0x5EED_2026L);
        for (int index = 0; index < ITERATIONS; index++) {
            Term control = control(random);
            Term message = random.nextBoolean() ? term(random, 0) : null;
            byte[] payload = DistConnection.encodeFrame(control, message);

            assertTrue("type byte is missing", payload.length > 0);
            assertEquals(112, payload[0] & 0xFF);
            DistConnection.checkFrameLength(payload.length);

            Term[] decoded = DistConnection.decodeFrame(payload);
            assertEquals(control, decoded[0]);
            assertEquals(message, decoded[1]);
        }
    }

    @Test
    public void invalidLengthsAreRejected() {
        expectInvalidLength(-1);
        expectInvalidLength(java.lang.Integer.MIN_VALUE);
        expectInvalidLength(DistConnection.MAX_PACKET + 1);
        try {
            // A zero length packet is the net tick and is valid.
            DistConnection.checkFrameLength(0);
        } catch (IOException e) {
            fail("zero length net ticks must stay valid: " + e);
        }
    }

    @Test
    public void malformedPayloadsFailAsIoExceptions() throws Exception {
        expectRejected(new byte[0]);
        expectRejected(new byte[] { 0 });
        expectRejected(new byte[] { 112 });

        byte[] wrongType = DistConnection.encodeFrame(
            Tuple.of(Integer.of(2), List.nil, new PID(PEER, 1, 0, 0)), Atom.of("hello"));
        wrongType[0] = 111;
        expectRejected(wrongType);

        byte[] truncated = DistConnection.encodeFrame(
            Tuple.of(Integer.of(2), List.nil, new PID(PEER, 1, 0, 0)),
            Binary.of(new byte[64]));
        expectRejected(java.util.Arrays.copyOf(truncated, truncated.length - 1));

        Random random = new Random(0xC0FFEE_2026L);
        for (int index = 0; index < ITERATIONS; index++) {
            byte[] payload = new byte[1 + random.nextInt(128)];
            random.nextBytes(payload);
            payload[0] = 112;
            try {
                Term[] decoded = DistConnection.decodeFrame(payload);
                assertNotNull("a decoded packet must contain a control term", decoded[0]);
            } catch (IOException expected) {
                // Rejection is the expected result for almost every random
                // payload. The point is that it is an IOException, not an
                // unchecked failure escaping into the connection loop.
            }
        }
    }

    private static Term control(Random random) {
        return Tuple.of(Integer.of(2 + random.nextInt(30)),
            new PID(PEER, 1 + random.nextInt(1000), 0, 0),
            List.nil, term(random, 0));
    }

    private static Term term(Random random, int depth) {
        int kind = random.nextInt(depth >= 2 ? 3 : 6);
        switch (kind) {
        case 0:
            return Atom.of("a" + random.nextInt(50));
        case 1:
            return Integer.of(random.nextInt(1_000_000));
        case 2:
            return Binary.of(randomBytes(random, random.nextInt(32)));
        case 3:
            return List.of(term(random, depth + 1), term(random, depth + 1));
        case 4:
            return Tuple.of(term(random, depth + 1), term(random, depth + 1));
        default:
            return List.nil;
        }
    }

    private static byte[] randomBytes(Random random, int length) {
        byte[] bytes = new byte[length];
        random.nextBytes(bytes);
        return bytes;
    }

    private static void expectInvalidLength(int length) {
        try {
            DistConnection.checkFrameLength(length);
            fail("accepted invalid frame length " + length);
        } catch (IOException expected) {
            // expected
        }
    }

    private static void expectRejected(byte[] payload) {
        try {
            DistConnection.decodeFrame(payload);
            fail("accepted a malformed payload of " + payload.length + " bytes");
        } catch (IOException expected) {
            // expected
        }
    }

}
