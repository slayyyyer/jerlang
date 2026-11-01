package org.jerlang.bench;

import org.jerlang.api.Node;

/**
 * Boots and closes a node, for the startup row of the benchmark comparison.
 *
 * It exists as a separate main class because startup has to include the JVM
 * itself: timing {@code Node.start()} inside the already running test JVM
 * would measure nothing but a scheduler thread pool.
 */
public final class StartupProbe {

    private StartupProbe() {
    }

    public static void main(String[] args) {
        try (Node node = Node.start()) {
            // Touch the node so the JIT can not reduce the boot to nothing.
            node.name();
        }
    }

}
