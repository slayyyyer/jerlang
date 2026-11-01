package org.jerlang.soak;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.Locale;

import org.jerlang.Module;
import org.jerlang.ModuleLoader;
import org.jerlang.ModuleRegistry;
import org.jerlang.api.Node;
import org.jerlang.api.NodeOptions;
import org.jerlang.type.Atom;
import org.jerlang.type.Integer;
import org.jerlang.vm.Metrics;
import org.junit.Assume;
import org.junit.Test;

/**
 * The soak test the 1.0 exit test requires: a node runs at target load for a
 * configured time and the runtime has to stay flat.
 *
 * "Flat" is measured, not felt. During the run the test samples the metrics and
 * checks the process count against what the worker count implies; after the
 * load ends it checks three things that a leak would break: the registry drains
 * back to its baseline, the used heap after a collection is within a bound of
 * where it started, and the open file descriptor count has not grown on a
 * system that reports descriptors.
 *
 * <pre>
 * mvn -Dtest=SoakTest -Djerlang.soak.seconds=60 test
 * mvn -Dtest=SoakTest -Djerlang.soak.seconds=86400 test   # the 1.0 gate
 * </pre>
 *
 * The duration is required, so the suite stays fast by default. The 24 hour run
 * can not live in GitHub Actions: a hosted job is killed after six hours, so
 * the release checklist runs it on a machine that stays up and CI runs a
 * shorter one nightly. That is a limit of the release process, not of this test.
 */
public class SoakTest {

    /** Above this, a "flat" run is a leak with extra steps. */
    private static final long HEAP_GROWTH_BYTES = 64L * 1024 * 1024;

    @Test
    public void sustainsLoadWithoutLeaking() throws Throwable {
        int seconds = java.lang.Integer.getInteger("jerlang.soak.seconds", 0);
        Assume.assumeTrue("set -Djerlang.soak.seconds=<n> to run the soak",
            seconds > 0);
        int workers = java.lang.Integer.getInteger("jerlang.soak.workers", 32);

        Path beam = Paths.get("src/test/resources/soak_worker.beam");
        Assume.assumeTrue("missing " + beam + "; compile the Erlang fixtures first",
            Files.isRegularFile(beam));
        Path local = Paths.get("soak_worker.beam");
        Files.copy(beam, local, StandardCopyOption.REPLACE_EXISTING);

        try (Node node = Node.start(NodeOptions.builder().schedulers(4).build())) {
            Atom name = Atom.of("soak_worker");
            Module module = ModuleLoader.load(name, local.toFile());
            Assume.assumeTrue("could not load soak_worker.beam", module != null);
            ModuleRegistry.register(module);

            long baselineProcesses = node.metrics().processes();
            long baselineHeap = usedHeap();
            long baselineFds = openFileDescriptors();

            // The load stops itself: the workers carry a deadline, so there is
            // no stop message to lose and nothing to leave behind if this
            // thread is interrupted.
            node.apply("soak_worker", "start", Integer.of(workers),
                Integer.of(seconds * 1000));

            System.out.printf(Locale.ROOT,
                "SOAK start: %d workers for %d s, baseline processes=%d fds=%d heap=%dMB%n",
                workers, seconds, baselineProcesses, baselineFds, baselineHeap >> 20);

            long startedAt = System.currentTimeMillis();
            long deadline = startedAt + seconds * 1000L;
            int samples = 0;
            long peakProcesses = baselineProcesses;
            long peakFds = baselineFds;
            long peakHeap = baselineHeap;
            Metrics metrics = node.metrics();

            while (System.currentTimeMillis() < deadline) {
                Thread.sleep(1000);
                metrics = node.metrics();
                samples++;
                long heap = usedHeap();
                peakProcesses = Math.max(peakProcesses, metrics.processes());
                peakHeap = Math.max(peakHeap, heap);
                long fds = openFileDescriptors();
                if (fds >= 0) {
                    peakFds = Math.max(peakFds, fds);
                }
                if (samples % 10 == 1 || samples == seconds) {
                    System.out.printf(Locale.ROOT,
                        "SOAK t=%3ds processes=%d mailboxes=%d delivered=%d rate=%.0f/s fds=%d heap=%dMB%n",
                        (System.currentTimeMillis() - startedAt) / 1000,
                        metrics.processes(), metrics.mailboxes(),
                        metrics.messagesDelivered(), metrics.messagesPerSecond(),
                        fds, heap >> 20);
                }
                // Two processes per worker (a driver and its echo) plus the
                // node's own, with headroom: the count is a function of the
                // worker count, not of how long the run has been going.
                assertTrue("processes grew under load: " + metrics,
                    metrics.processes() <= baselineProcesses + 2L * workers + 32);
            }

            assertTrue("the load produced no traffic: " + metrics,
                metrics.messagesDelivered() > 0);

            // The drivers stop at their deadline; the registry must drain when
            // they do.
            long drained = waitForProcesses(node, baselineProcesses, 30_000);
            assertEquals("registry did not drain after the load; processes left",
                baselineProcesses, drained);

            long finalHeap = usedHeap();
            long growth = finalHeap - baselineHeap;
            System.out.printf(Locale.ROOT,
                "SOAK done: %d samples, peak processes=%d, peak fds=%d, delivered=%d, "
                    + "peak heap=%dMB, final heap %dMB -> %dMB%n",
                samples, peakProcesses, peakFds, metrics.messagesDelivered(),
                peakHeap >> 20, baselineHeap >> 20, finalHeap >> 20);
            assertTrue("heap grew by " + (growth >> 20) + "MB over the run, limit is "
                + (HEAP_GROWTH_BYTES >> 20) + "MB", growth < HEAP_GROWTH_BYTES);

            if (baselineFds >= 0) {
                long finalFds = openFileDescriptors();
                assertTrue("open file descriptors grew from " + baselineFds + " to " + finalFds,
                    finalFds <= baselineFds + 8);
            } else {
                System.out.println("SOAK fds: not observable on " + System.getProperty("os.name"));
            }
        } finally {
            Files.deleteIfExists(local);
        }
    }

    private static long waitForProcesses(Node node, long baseline, long timeoutMillis)
        throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMillis;
        long processes = node.metrics().processes();
        while (processes > baseline + 1 && System.currentTimeMillis() < deadline) {
            Thread.sleep(100);
            processes = node.metrics().processes();
        }
        return processes;
    }

    /** Open file descriptors, or -1 where the platform does not expose them. */
    private static long openFileDescriptors() {
        Path directory = Paths.get("/proc/self/fd");
        if (!Files.isDirectory(directory)) {
            return -1;
        }
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(directory)) {
            long count = 0;
            for (Path ignored : stream) {
                count++;
            }
            return count;
        } catch (IOException e) {
            return -1;
        }
    }

    private static long usedHeap() {
        Runtime runtime = Runtime.getRuntime();
        for (int attempt = 0; attempt < 3; attempt++) {
            System.gc();
            try {
                Thread.sleep(50);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        return runtime.totalMemory() - runtime.freeMemory();
    }

}
