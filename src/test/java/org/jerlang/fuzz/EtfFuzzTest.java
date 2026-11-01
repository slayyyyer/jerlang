package org.jerlang.fuzz;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Random;

import org.jerlang.erts.etf.ExternalTermReader;
import org.jerlang.erts.etf.ExternalTermWriter;
import org.jerlang.type.Atom;
import org.jerlang.type.Binary;
import org.jerlang.type.Float;
import org.jerlang.type.Integer;
import org.jerlang.type.Str;
import org.jerlang.type.Term;
import org.jerlang.type.Tuple;
import org.junit.Test;

/**
 * Fuzzes the external term format decoder, which is the first thing every
 * distribution message and every stored term passes through.
 *
 * The contract: decode the bytes or fail with an error the caller can report.
 * A stream that claims a colossal list, a negative length or a bit length past
 * the end of its buffer must be refused, not allocated. Version-negotiation is
 * deliberately out of scope: the decoder sees bytes that already passed a
 * frame boundary, so this feeds it exactly that.
 */
public class EtfFuzzTest {

    @Test
    public void mutatedTermsAreDecodedOrRefused() {
        Term[] terms = {
            Integer.of(0),
            Integer.of(-42),
            Atom.of("atom"),
            Str.of("a string"),
            Float.of(1.5),
            Binary.of(new byte[] { 0, 1, (byte) 255 }),
            Tuple.of(Atom.of("t"), Integer.of(1), Str.of("x")),
            org.jerlang.type.List.of(Integer.of(1), Integer.of(2)),
        };

        int iterations = FuzzSupport.iterations();
        Random random = new Random(FuzzSupport.seed());
        Map<String, java.lang.Integer> refusals = new LinkedHashMap<>();
        int decoded = 0;
        int refused = 0;

        for (Term term : terms) {
            byte[] encoded = ExternalTermWriter.encode(term).toByteArray();
            for (int iteration = 0; iteration < iterations; iteration++) {
                byte[] mutated = FuzzSupport.mutate(encoded, random);
                try {
                    Term result = new ExternalTermReader(mutated).read();
                    assertTrue("iteration " + iteration + " decoded to null", result != null);
                    decoded++;
                } catch (Throwable failure) {
                    FuzzSupport.assertNotFatal(failure,
                        "iteration " + iteration + " of " + term
                            + " (seed " + FuzzSupport.seed() + ")");
                    refused++;
                    refusals.merge(FuzzSupport.describe(failure), 1, java.lang.Integer::sum);
                }
            }
        }

        System.out.printf(Locale.ROOT,
            "FUZZ etf decoder: %d terms, %d mutations, %d decoded, %d refused%n",
            terms.length, terms.length * iterations, decoded, refused);
        refusals.forEach((reason, count) ->
            System.out.println("  " + reason + ": " + count));

        assertEquals("every mutation must be counted",
            terms.length * iterations, decoded + refused);
        assertTrue("no mutation was ever refused: the mutator is not mutating",
            refused > 0);
    }

}
