package org.jerlang;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

import org.jerlang.type.Atom;
import org.jerlang.type.Integer;
import org.jerlang.type.List;
import org.jerlang.type.PID;
import org.jerlang.type.Term;
import org.jerlang.type.Tuple;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

public class TracingTest {

    private RecordingProcess target;
    private FunctionSignature signature;

    @Before
    public void setUp() {
        Tracing.reset();
        target = new RecordingProcess(9002);
        target.setTraceFlag(Tracing.CALL, true);
        target.setTraceFlag(Tracing.RETURN_TO, true);
        signature = new FunctionSignature(Atom.of("trace_demo"), Atom.of("f"),
            Integer.of(2));
    }

    @After
    public void tearDown() {
        Tracing.reset();
    }

    @Test
    public void matchSpecFiltersCallsAndBodySuppliesTheMessage() {
        Term pattern = List.of(Atom.of("$1"), Integer.of(2));
        Term body = Tuple.of(Atom.of("message"),
            Tuple.of(Atom.of("custom"), Atom.of("$1")));
        Term specification = List.of(Tuple.of(pattern, List.nil, body));
        Tracing.pattern(Tuple.of(signature.module(), signature.function(),
            signature.fun_arity()), specification, List.of(Atom.of("return_trace")));

        assertNotNull(Tracing.beforeCall(target, signature,
            List.of(Integer.of(1), Integer.of(2))));
        assertEquals(Tuple.of(Atom.of("custom"), Integer.of(1)), target.last);

        assertNull("a non-matching argument list must not trace",
            Tracing.beforeCall(target, signature,
                List.of(Integer.of(1), Integer.of(3))));

        Tracing.afterReturn(target, signature);
        assertEquals(Tuple.of(Tracing.TRACE, target.pid(), Tracing.RETURN_TO,
            Tuple.of(signature.module(), signature.function(), signature.fun_arity())),
            target.last);
    }

    private static final class RecordingProcess extends Process {

        private Term last;

        RecordingProcess(int number) {
            super(new PID(number));
        }

        @Override
        public void trace(Term event) {
            last = event;
        }

    }

}
