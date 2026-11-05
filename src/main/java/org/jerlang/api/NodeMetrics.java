package org.jerlang.api;

/**
 * MBean implementation. The node owns the registration lifetime; this class
 * does not cache counters, so a JMX client reads the same snapshot as
 * {@link Node#metrics()}.
 */
public final class NodeMetrics implements NodeMetricsMBean {

    private final Node node;

    NodeMetrics(Node node) {
        this.node = node;
    }

    @Override
    public long getProcesses() {
        return node.metrics().processes();
    }

    @Override
    public long getPorts() {
        return node.metrics().ports();
    }

    @Override
    public long getReductions() {
        return node.metrics().reductions();
    }

    @Override
    public long getMailboxMessages() {
        return node.metrics().mailboxes();
    }

    @Override
    public long getMessagesDelivered() {
        return node.metrics().messagesDelivered();
    }

    @Override
    public double getMessagesPerSecond() {
        return node.metrics().messagesPerSecond();
    }

    @Override
    public long getRunQueueDepth() {
        return node.metrics().runQueueDepth();
    }

    @Override
    public long getUptimeMillis() {
        return node.metrics().uptimeMillis();
    }

    @Override
    public int getSchedulerCount() {
        return node.metrics().schedulerCount();
    }

    @Override
    public double getSchedulerUtilisation() {
        return node.metrics().utilisation();
    }

    @Override
    public String getText() {
        return node.metrics().toText();
    }

}
