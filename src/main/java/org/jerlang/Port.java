package org.jerlang;

import org.jerlang.erts.driver.Driver;
import org.jerlang.type.Integer;
import org.jerlang.type.PortID;
import org.jerlang.type.Term;

public abstract class Port extends ProcessOrPort {

    private final Driver driver;

    public Port(PortID portId, Driver driver) {
        super(portId);
        this.driver = driver;
    }

    public Term control(Integer operation, Term data) {
        return null;
    }

    /**
     * Release the resource behind this port, if it has one.
     */
    public void close() {
    }

    @Override
    public Port toPort() {
        return this;
    }

}
