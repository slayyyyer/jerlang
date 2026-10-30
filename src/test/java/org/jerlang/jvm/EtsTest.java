package org.jerlang.jvm;

import static org.junit.Assert.assertEquals;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;

import org.jerlang.erts.Erlang;
import org.jerlang.type.Atom;
import org.jerlang.type.List;
import org.jerlang.type.Term;
import org.jerlang.type.Tuple;
import org.jerlang.vm.VirtualMachine;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/**
 * Runs ets_demo.beam, compiled from src/test/erl/ets_demo.erl by Erlang/OTP
 * 29, against the built-in ets module.
 *
 * The table is created from Erlang, so these tests also cover that ets:new/2
 * and the rest of the module are reachable at all: before it was registered,
 * every ets call was undef regardless of how much of the facade existed.
 */
public class EtsTest {

    private static final Path SOURCE = Paths.get("src/test/resources/ets_demo.beam");
    private static final Path LOCAL = Paths.get("ets_demo.beam");
    private static final Atom ETS_DEMO = Atom.of("ets_demo");

    @Before
    public void start() throws Exception {
        Files.copy(SOURCE, LOCAL, StandardCopyOption.REPLACE_EXISTING);
        VirtualMachine.instance().start();
    }

    @After
    public void stop() throws Exception {
        VirtualMachine.instance().shutdown();
        Files.deleteIfExists(LOCAL);
    }

    @Test
    public void testStoreAndLookupFromCompiledErlang() throws Throwable {
        assertEquals(Atom.of("ok"), apply("store_and_lookup"));
    }

    @Test
    public void testLaterObjectReplacesTheSameKey() throws Throwable {
        assertEquals(Atom.of("ok"), apply("overwrite"));
    }

    @Test
    public void testInsertAListOfObjects() throws Throwable {
        assertEquals(Atom.of("ok"), apply("insert_many"));
    }

    @Test
    public void testKeysOfDifferentTypesAreKeptApart() throws Throwable {
        assertEquals(Atom.of("ok"), apply("mixed_keys"));
    }

    @Test
    public void testIntegerAndFloatKeysAreDistinct() throws Throwable {
        assertEquals(Atom.of("ok"), apply("integer_and_float_keys_are_distinct"));
    }

    @Test
    public void testMissingKeyReturnsEmptyList() throws Throwable {
        assertEquals(Atom.of("ok"), apply("missing_key"));
    }

    @Test
    public void testNonTupleObjectIsBadarg() throws Throwable {
        assertEquals(Atom.of("ok"), apply("bad_object"));
    }

    @Test
    public void testInsertIntoMissingTableIsBadarg() throws Throwable {
        assertEquals(Atom.of("ok"), apply("unknown_table"));
    }

    @Test
    public void testStoredObjectIsNotAliasedBySetelement() throws Throwable {
        assertEquals(Atom.of("ok"), apply("setelement_does_not_change_table"));
    }

    @Test
    public void testDeleteKeyFromCompiledErlang() throws Throwable {
        assertEquals(Atom.of("ok"), apply("delete_key"));
    }

    @Test
    public void testDeleteMissingKeyIsNotAnError() throws Throwable {
        assertEquals(Atom.of("ok"), apply("delete_missing_key"));
    }

    @Test
    public void testDeletedTableStopsWorking() throws Throwable {
        assertEquals(Atom.of("ok"), apply("delete_table"));
    }

    @Test
    public void testDeleteOnMissingTableIsBadarg() throws Throwable {
        assertEquals(Atom.of("ok"), apply("delete_unknown_table"));
    }

    @Test
    public void testTab2listReturnsEveryObject() throws Throwable {
        assertEquals(Atom.of("ok"), apply("tab2list_all"));
    }

    @Test
    public void testTab2listOnEmptyTable() throws Throwable {
        assertEquals(Atom.of("ok"), apply("tab2list_empty"));
    }

    @Test
    public void testTab2listOnMissingTableIsBadarg() throws Throwable {
        assertEquals(Atom.of("ok"), apply("tab2list_unknown_table"));
    }

    @Test
    public void testNamedTableIsAddressedByName() throws Throwable {
        assertEquals(Atom.of("ok"), apply("named_table"));
    }

    @Test
    public void testNameBelongsToOneTableAtATime() throws Throwable {
        assertEquals(Atom.of("ok"), apply("named_table_is_exclusive"));
    }

    @Test
    public void testDeletingANamedTableFreesTheName() throws Throwable {
        assertEquals(Atom.of("ok"), apply("delete_frees_the_name"));
    }

    @Test
    public void testKeyposOption() throws Throwable {
        assertEquals(Atom.of("ok"), apply("keypos_option"));
    }

    @Test
    public void testObjectShorterThanKeyposIsBadarg() throws Throwable {
        assertEquals(Atom.of("ok"), apply("keypos_short_object_is_badarg"));
    }

    @Test
    public void testInvalidKeyposIsBadarg() throws Throwable {
        assertEquals(Atom.of("ok"), apply("invalid_keypos_is_badarg"));
    }

    @Test
    public void testUpdateCounterIncrements() throws Throwable {
        assertEquals(Atom.of("ok"), apply("update_counter_increments"));
    }

    @Test
    public void testUpdateCounterDefaultCreatesObject() throws Throwable {
        assertEquals(Atom.of("ok"), apply("update_counter_default"));
    }

    @Test
    public void testUpdateCounterThresholds() throws Throwable {
        assertEquals(Atom.of("ok"), apply("update_counter_thresholds"));
    }

    @Test
    public void testUpdateCounterMultipleOperations() throws Throwable {
        assertEquals(Atom.of("ok"), apply("update_counter_multiple"));
    }

    @Test
    public void testUpdateCounterFollowsKeypos() throws Throwable {
        assertEquals(Atom.of("ok"), apply("update_counter_keypos"));
    }

    @Test
    public void testUpdateCounterEmptyOperationList() throws Throwable {
        assertEquals(Atom.of("ok"), apply("update_counter_empty_ops"));
    }

    @Test
    public void testUpdateCounterBadargCases() throws Throwable {
        assertEquals(Atom.of("ok"), apply("update_counter_badarg"));
    }

    @Test
    public void testOrderedSetIsSorted() throws Throwable {
        assertEquals(Atom.of("ok"), apply("ordered_set_is_sorted"));
    }

    @Test
    public void testOrderedSetUsesTermOrder() throws Throwable {
        assertEquals(Atom.of("ok"), apply("ordered_set_uses_term_order"));
    }

    @Test
    public void testMatchReturnsBoundVariables() throws Throwable {
        assertEquals(Atom.of("ok"), apply("match_returns_bound_variables"));
    }

    @Test
    public void testMatchObjectReturnsObjects() throws Throwable {
        assertEquals(Atom.of("ok"), apply("match_object_returns_objects"));
    }

    @Test
    public void testMatchHonoursLimit() throws Throwable {
        assertEquals(Atom.of("ok"), apply("match_honours_limit"));
    }

    @Test
    public void testSelectProjectsTheBody() throws Throwable {
        assertEquals(Atom.of("ok"), apply("select_projects_the_body"));
    }

    @Test
    public void testSelectGuards() throws Throwable {
        assertEquals(Atom.of("ok"), apply("select_guards"));
    }

    @Test
    public void testSelectMultipleClauses() throws Throwable {
        assertEquals(Atom.of("ok"), apply("select_multiple_clauses"));
    }

    @Test
    public void testSelectBadSpecification() throws Throwable {
        assertEquals(Atom.of("ok"), apply("select_bad_specification"));
    }

    @Test
    public void testGiveAwayTransfersOwnership() throws Throwable {
        assertEquals(Atom.of("ok"), apply("give_away_transfers_ownership"));
    }

    @Test
    public void testGiveAwayToSelfIsBadarg() throws Throwable {
        assertEquals(Atom.of("ok"), apply("give_away_to_self_is_badarg"));
    }

    @Test
    public void testGiveAwayRequiresTheOwner() throws Throwable {
        assertEquals(Atom.of("ok"), apply("give_away_requires_the_owner"));
    }

    /**
     * The same insert and lookup, called from Java, so a failure points at
     * the table and not at the interpreter path that reaches it.
     */
    @Test
    public void testInsertAndLookupFromJava() throws Throwable {
        Term tab = Erlang.apply(Atom.of("ets"), Atom.of("new"),
            List.of(Atom.of("ets_test_java"), List.nil));
        Term object = Tuple.of(Atom.of("key"), Atom.of("value"));

        assertEquals(Atom.of("true"), Erlang.apply(Atom.of("ets"), Atom.of("insert"),
            List.of(tab, object)));
        assertEquals(List.of(object), Erlang.apply(Atom.of("ets"), Atom.of("lookup"),
            List.of(tab, Atom.of("key"))));
    }

    private Term apply(String function) throws Throwable {
        return Erlang.apply(ETS_DEMO, Atom.of(function), List.nil);
    }

}
