package org.jerlang;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertSame;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.jerlang.stdlib.Code;
import org.jerlang.type.Atom;
import org.jerlang.type.Term;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/**
 * Loading is a shared operation: a server can start several modules at once,
 * and an embedding host can call code:ensure_loaded/1 from more than one Java
 * thread. The registry must publish exactly one Module for a name and must not
 * corrupt the atom table while doing it.
 */
public class ConcurrentLoadingTest {

    private static final Atom MODULE = Atom.of("constructs_demo");
    private static final Atom OTHER = Atom.of("compare_demo");
    private static final Path SOURCE = Paths.get("src/test/resources/constructs_demo.beam");
    private static final Path OTHER_SOURCE = Paths.get("src/test/resources/compare_demo.beam");
    private static final Path LOCAL = Paths.get("constructs_demo.beam");
    private static final Path OTHER_LOCAL = Paths.get("compare_demo.beam");

    @Before
    public void copyFixture() throws Exception {
        Files.copy(SOURCE, LOCAL, StandardCopyOption.REPLACE_EXISTING);
        Files.copy(OTHER_SOURCE, OTHER_LOCAL, StandardCopyOption.REPLACE_EXISTING);
        ModuleRegistry.reset();
    }

    @After
    public void cleanup() throws Exception {
        ModuleRegistry.reset();
        Files.deleteIfExists(LOCAL);
        Files.deleteIfExists(OTHER_LOCAL);
    }

    @Test
    public void concurrentLoadsReturnTheSameModule() throws Exception {
        int workers = 8;
        ExecutorService pool = Executors.newFixedThreadPool(workers);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Module>> futures = new ArrayList<>();
        try {
            for (int worker = 0; worker < workers; worker++) {
                futures.add(pool.submit(() -> {
                    start.await();
                    return ModuleRegistry.get(MODULE);
                }));
            }
            start.countDown();

            Set<Module> identities = Collections.newSetFromMap(new IdentityHashMap<>());
            for (Future<Module> future : futures) {
                Module module = future.get(30, TimeUnit.SECONDS);
                assertNotNull("the fixture did not load", module);
                identities.add(module);
            }
            assertEquals("one module instance must win the race", 1, identities.size());
            assertSame(identities.iterator().next(), ModuleRegistry.loaded(MODULE));
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    public void ensureModulesLoadedLoadsEveryName() {
        Term result = Code.ensure_modules_loaded(org.jerlang.type.List.of(MODULE, OTHER));
        assertEquals(Atom.of("ok"), result);
        assertNotNull(ModuleRegistry.loaded(MODULE));
        assertNotNull(ModuleRegistry.loaded(OTHER));
    }

}
