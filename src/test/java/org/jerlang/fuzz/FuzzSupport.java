package org.jerlang.fuzz;

import java.util.Arrays;
import java.util.Random;

/**
 * Shared machinery for the three fuzzers the 1.0 milestone asks for.
 *
 * The fuzzers are deterministic: every run mutates the same inputs in the same
 * order for a given seed, so a finding reproduces from the seed and iteration
 * number printed with it instead of "it failed once on CI". The iteration count
 * is small enough to run on every push and can be raised for a longer campaign:
 *
 * <pre>
 * mvn -Dtest=BeamLoaderFuzzTest -Djerlang.fuzz.iterations=20000 test
 * </pre>
 */
final class FuzzSupport {

    private FuzzSupport() {
    }

    static int iterations() {
        return java.lang.Integer.getInteger("jerlang.fuzz.iterations", 200);
    }

    static long seed() {
        return Long.getLong("jerlang.fuzz.seed", 20261101L);
    }

    /**
     * A copy of the input with a few byte edits, a truncation, or an appended
     * tail. Small numbers of edits matter: a decoder that crashes on one wrong
     * byte is the interesting case, not one that crashes on random noise where
     * the version byte alone rejects it.
     */
    static byte[] mutate(byte[] original, Random random) {
        byte[] copy = original.clone();
        int edits = 1 + random.nextInt(3);
        for (int edit = 0; edit < edits; edit++) {
            int position = random.nextInt(copy.length);
            int kind = random.nextInt(3);
            if (kind == 0) {
                copy[position] = (byte) random.nextInt(256);
            } else if (kind == 1) {
                copy[position] ^= (byte) (1 << random.nextInt(8));
            } else {
                copy[position] = 0;
            }
        }
        int shape = random.nextInt(4);
        if (shape == 0 && copy.length > 8) {
            return Arrays.copyOf(copy, 8 + random.nextInt(copy.length - 8));
        }
        if (shape == 1) {
            byte[] longer = Arrays.copyOf(copy, copy.length + 1 + random.nextInt(16));
            return longer;
        }
        return copy;
    }

    /**
     * A fuzzer exists to find crashes, not to tolerate them: a JVM-level error
     * is a finding even when the framework above it catches Throwable, because
     * catching it hides an out of memory or a runaway recursion instead of
     * reporting a malformed input.
     */
    static void assertNotFatal(Throwable failure, String context) {
        if (failure instanceof OutOfMemoryError
            || failure instanceof StackOverflowError
            || failure instanceof LinkageError
            || failure instanceof InternalError) {
            throw new AssertionError(context + " raised " + failure, failure);
        }
    }

    static String describe(Throwable failure) {
        return failure.getClass().getSimpleName()
            + (failure.getMessage() == null ? "" : "(" + failure.getMessage() + ")");
    }

}
