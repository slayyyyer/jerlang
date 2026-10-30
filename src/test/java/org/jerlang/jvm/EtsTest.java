package org.jerlang.jvm;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.jerlang.Process;
import org.jerlang.ProcessOrPort;
import org.jerlang.ProcessRegistry;
import org.jerlang.ProcessSignals;
import org.jerlang.erts.Erlang;
import org.jerlang.stdlib.Ets;
import org.jerlang.stdlib.ets.TableRegistry;
import org.jerlang.type.Atom;
import org.jerlang.type.Integer;
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

    @Test
    public void testTableDiesWithItsOwnerFromCompiledErlang() throws Throwable {
        assertEquals(Atom.of("ok"), apply("table_dies_with_owner"));
    }

    /**
     * The same insert and lookup, called from Java, so a failure points at
     * the table and not at the interpreter path that reaches it.
     *
     * The table is created on the test thread, which is not an Erlang
     * process, so it has no owner and outlives the calls. Creating it through
     * erlang:apply would put it in a temporary process that terminates when
     * the call returns, and the table would (correctly) die with it.
     */
    @Test
    public void testInsertAndLookupFromJava() throws Throwable {
        Term tab = Ets._new(Atom.of("ets_test_java"), List.nil);
        Term object = Tuple.of(Atom.of("key"), Atom.of("value"));

        assertEquals(Atom.of("true"), Ets.insert(tab, object));
        assertEquals(List.of(object), Ets.lookup(tab, Atom.of("key")));
        Ets.delete(tab);
    }

    /**
     * A table is destroyed with the process that owns it, and give_away moves
     * the ownership so the old owner can terminate without taking it down.
     */
    @Test
    public void testTableDiesWithItsOwner() throws Throwable {
        VirtualMachine machine = VirtualMachine.instance();
        Process owner = new Process(machine.uselessPid());
        Process keeper = new Process(machine.uselessPid());
        ProcessRegistry.register(owner);
        ProcessRegistry.register(keeper);

        ProcessOrPort previous = ProcessRegistry.self();
        try {
            ProcessRegistry.self(owner);
            Atom name = Atom.of("ets_owner_lifetime");
            assertEquals(name, Ets._new(name, List.of(Atom.of("named_table"))));
            assertNotNull(TableRegistry.lookup(name));
            assertEquals(Atom.of("true"), Ets.give_away(name, keeper.pid(), Atom.of("gift")));
        } finally {
            ProcessRegistry.self(previous);
        }

        ProcessSignals.terminate(owner, Atom.of("normal"));
        assertNotNull("a given-away table survives its previous owner",
            TableRegistry.lookup(Atom.of("ets_owner_lifetime")));

        ProcessSignals.terminate(keeper, Atom.of("normal"));
        assertNull("a table dies with its owner",
            TableRegistry.lookup(Atom.of("ets_owner_lifetime")));

        // The name is free again, which is what lets a restarted gen_server
        // recreate the table its init/1 creates.
        Atom name = Atom.of("ets_owner_lifetime");
        assertEquals(name, Ets._new(name, List.of(Atom.of("named_table"))));
        Ets.delete(name);
    }

    /**
     * update_counter is a read-modify-write. Several processes incrementing
     * one key must not lose an increment.
     */
    @Test
    public void testConcurrentUpdateCounterKeepsEveryIncrement() throws Exception {
        Atom name = Atom.of("ets_concurrent_counter");
        Term tab = Ets._new(name, List.of(Atom.of("named_table")));
        Term key = Atom.of("counter");
        Ets.insert(tab, Tuple.of(key, Integer.of(0)));

        int workers = 8;
        int perWorker = 500;
        ExecutorService pool = Executors.newFixedThreadPool(workers);
        CountDownLatch start = new CountDownLatch(1);
        java.util.List<Future<?>> futures = new ArrayList<>();
        try {
            for (int worker = 0; worker < workers; worker++) {
                futures.add(pool.submit(() -> {
                    start.await();
                    for (int i = 0; i < perWorker; i++) {
                        Ets.update_counter(tab, key, Tuple.of(Integer.of(2), Integer.of(1)));
                    }
                    return null;
                }));
            }
            start.countDown();
            for (Future<?> future : futures) {
                future.get(30, TimeUnit.SECONDS);
            }
            assertEquals(List.of(Tuple.of(key, Integer.of(workers * perWorker))),
                Ets.lookup(tab, key));
        } finally {
            pool.shutdownNow();
            Ets.delete(name);
        }
    }

    /**
     * Two processes racing to create the same named table: exactly one wins,
     * the rest get badarg. A check-then-put could let both succeed and leave
     * the name pointing at only one of them.
     */
    @Test
    public void testConcurrentNamedTableCreationHasOneWinner() throws Exception {
        Atom name = Atom.of("ets_named_race");
        int workers = 8;
        ExecutorService pool = Executors.newFixedThreadPool(workers);
        CountDownLatch start = new CountDownLatch(1);
        java.util.List<Future<Boolean>> futures = new ArrayList<>();
        try {
            for (int worker = 0; worker < workers; worker++) {
                futures.add(pool.submit(() -> {
                    start.await();
                    try {
                        Ets._new(name, List.of(Atom.of("named_table")));
                        return true;
                    } catch (org.jerlang.erts.erlang.Error expected) {
                        return false;
                    }
                }));
            }
            start.countDown();
            int winners = 0;
            for (Future<Boolean> future : futures) {
                if (future.get(30, TimeUnit.SECONDS)) {
                    winners++;
                }
            }
            assertEquals("exactly one ets:new may claim a name", 1, winners);
        } finally {
            pool.shutdownNow();
            Ets.delete(name);
        }
    }

    private Term apply(String function) throws Throwable {
        return Erlang.apply(ETS_DEMO, Atom.of(function), List.nil);
    }

}
