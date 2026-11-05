package org.jerlang.api;

/**
 * JMX view of a running node. Each getter takes a fresh snapshot; the
 * attributes describe the node now, not a cumulative history.
 */
public interface NodeMetricsMBean {

    long getProcesses();

    long getPorts();

    long getReductions();

    long getMailboxMessages();

    long getMessagesDelivered();

    double getMessagesPerSecond();

    long getRunQueueDepth();

    long getUptimeMillis();

    int getSchedulerCount();

    double getSchedulerUtilisation();

    /** The same line-oriented snapshot as {@code Node.metrics().toText()}. */
    String getText();

}
