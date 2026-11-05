package org.jerlang.api;

import java.lang.management.ManagementFactory;

import javax.management.MBeanServer;
import javax.management.ObjectName;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class NodeMetricsTest {

    @Test
    public void registersLiveAttributesAndRemovesThemOnClose() throws Exception {
        MBeanServer server = ManagementFactory.getPlatformMBeanServer();
        ObjectName query = new ObjectName("org.jerlang:type=Node,*");
        int before = server.queryNames(query, null).size();

        Node node = Node.start();
        ObjectName name = new ObjectName("org.jerlang:type=Node,name="
            + ObjectName.quote(node.name().toString()));
        try {
            assertTrue("node MBean was not registered; names="
                + server.queryNames(query, null), server.isRegistered(name));
            assertEquals(1, server.queryNames(query, null).size() - before);
            assertTrue((Long) server.getAttribute(name, "Processes") >= 0);
            assertTrue((Integer) server.getAttribute(name, "SchedulerCount") > 0);
            assertTrue(((String) server.getAttribute(name, "Text"))
                .contains("jerlang_processes"));
        } finally {
            node.close();
        }
        assertTrue("node MBean survived close", !server.isRegistered(name));
    }

}
