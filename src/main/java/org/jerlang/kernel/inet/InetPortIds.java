package org.jerlang.kernel.inet;

import java.util.concurrent.atomic.AtomicInteger;

import org.jerlang.type.PortID;

/**
 * One identifier sequence for every socket port owned by the inet
 * registry. TCP and UDP sockets must not share a PortID.
 */
final class InetPortIds {

    private static final AtomicInteger NEXT_PORT = new AtomicInteger(1);

    private InetPortIds() {
    }

    static PortID next() {
        return new PortID(NEXT_PORT.getAndIncrement());
    }

}
