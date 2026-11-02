package org.jerlang;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.jerlang.type.Atom;
import org.jerlang.type.Boolean;
import org.jerlang.type.Integer;
import org.jerlang.type.Map;
import org.jerlang.type.PID;
import org.junit.Test;

public class HeapLimitTest {

    @Test
    public void aSmallLimitIsExceededAndKills() {
        Process process = new Process(new PID(1));
        Map limit = new Map();
        limit.set(Atom.of("size"), Integer.of(1));
        limit.set(Atom.of("kill"), Boolean.am_true);
        process.setMaxHeapSize(limit);

        assertTrue(process.exceedsMaxHeapSize());
        assertTrue(process.maxHeapSizeKills());
        process.killForHeapLimit();
        assertEquals(Atom.of("killed"), process.pendingExitReason());
    }

    @Test
    public void zeroMeansNoLimit() {
        Process process = new Process(new PID(1));
        Map limit = new Map();
        limit.set(Atom.of("size"), Integer.ZERO);
        limit.set(Atom.of("kill"), Boolean.am_true);
        process.setMaxHeapSize(limit);

        assertFalse(process.exceedsMaxHeapSize());
    }

}
