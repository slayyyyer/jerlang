package org.jerlang.stdlib.binary;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.fail;

import java.math.BigInteger;

import org.jerlang.erts.erlang.Error;
import org.jerlang.type.Atom;
import org.jerlang.type.Binary;
import org.jerlang.type.Integer;
import org.jerlang.type.List;
import org.jerlang.type.Tuple;
import org.junit.Test;

/**
 * binary:bin_to_list/1,2,3.
 *
 * The expected values are the ones Erlang/OTP 29 returns for the same calls.
 * The suite itself needs no Erlang installation.
 */
public class BinaryBinToListTest {

    private static final Binary SUBJECT = Binary.of(0, 1, 2, 3, 4);

    @Test
    public void binToList1ReturnsEveryByte() {
        assertEquals(List.of(Integer.of(0), Integer.of(1), Integer.of(2),
            Integer.of(3), Integer.of(4)), BinaryBinToList.bin_to_list_1(SUBJECT));
    }

    @Test
    public void binToList2StartsAtPosAndHonoursLen() {
        assertEquals(List.of(Integer.of(1), Integer.of(2)),
            BinaryBinToList.bin_to_list_2(SUBJECT, Tuple.of(Integer.of(1), Integer.of(2))));
        assertEquals(List.of(Integer.of(3)),
            BinaryBinToList.bin_to_list_2(SUBJECT, Tuple.of(Integer.of(3), Integer.of(1))));
    }

    @Test
    public void binToList2AcceptsAnEmptySliceAtTheEnd() {
        assertEquals(List.nil, BinaryBinToList.bin_to_list_2(SUBJECT,
            Tuple.of(Integer.of(5), Integer.of(0))));
    }

    @Test
    public void binToList3MatchesTheTupleForm() {
        assertEquals(BinaryBinToList.bin_to_list_2(SUBJECT,
            Tuple.of(Integer.of(2), Integer.of(3))),
            BinaryBinToList.bin_to_list_3(SUBJECT, Integer.of(2), Integer.of(3)));
        assertEquals(List.of(Integer.of(2), Integer.of(3), Integer.of(4)),
            BinaryBinToList.bin_to_list_3(SUBJECT, Integer.of(2), Integer.of(3)));
    }

    @Test
    public void outOfRangeSlicesRaiseBadarg() {
        assertBadarg(SUBJECT, 6, 0);
        assertBadarg(SUBJECT, 5, 1);
        assertBadarg(SUBJECT, 0, 6);
        assertBadarg(SUBJECT, 4, 2);
        assertBadarg(SUBJECT, -1, 1);
        assertBadarg(SUBJECT, 0, -1);
        // Neither the int sum nor a bignum may wrap into a plausible bound.
        assertBadarg(SUBJECT, 1 << 30, 1 << 30);
        assertBadarg(SUBJECT, Tuple.of(new Integer(BigInteger.ONE.shiftLeft(32)),
            Integer.ZERO));
    }

    @Test
    public void aMalformedPartRaisesBadarg() {
        assertBadarg(SUBJECT, Tuple.of(Integer.of(1)));
        assertBadarg(SUBJECT, Tuple.of(Integer.of(1), Integer.of(2), Integer.of(3)));
        assertBadarg(SUBJECT, Tuple.of(Integer.of(1), Atom.of("two")));
    }

    private static void assertBadarg(Binary subject, int pos, int len) {
        assertBadarg(subject, Tuple.of(Integer.of(pos), Integer.of(len)));
    }

    private static void assertBadarg(Binary subject, Tuple part) {
        try {
            BinaryBinToList.bin_to_list_2(subject, part);
            fail("expected badarg for " + part);
        } catch (Error error) {
            assertSame(Error.badarg, error);
        }
    }

}
