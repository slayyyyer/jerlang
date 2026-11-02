package org.jerlang;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

import java.util.ArrayList;

import org.jerlang.erts.erlang.Error;
import org.jerlang.type.Atom;
import org.jerlang.type.Boolean;
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
        signature = new FunctionSignature(Atom.of("trace_demo"), Atom.of("f"),
            Integer.of(2));
    }

    @After
    public void tearDown() {
        Tracing.reset();
    }

    @Test
    public void bodyActionsSelectReturnFromAndMessage() {
        Term body = List.of(
            Tuple.of(Atom.of("message"),
                Tuple.of(Atom.of("custom"), Atom.of("$1"))),
            Tuple.of(Atom.of("return_trace")));
        Term specification = List.of(Tuple.of(
            List.of(Atom.of("$1"), Integer.of(2)), List.nil, body));
        Tracing.pattern(Tuple.of(signature.module(), signature.function(),
            signature.fun_arity()), specification, List.nil);

        assertNotNull(Tracing.beforeCallDecision(target, signature,
            List.of(Integer.of(1), Integer.of(2))));
        assertEquals(Tuple.of(Atom.of("custom"), Integer.of(1)), target.last());

        assertNull("a non-matching argument list must not trace",
            Tracing.beforeCallDecision(target, signature,
                List.of(Integer.of(1), Integer.of(3))));

        Tracing.afterReturn(target, signature, Integer.of(7));
        assertEquals(Tuple.of(Tracing.TRACE, target.pid(), Tracing.RETURN_FROM,
            Tuple.of(signature.module(), signature.function(), signature.fun_arity()),
            Integer.of(7)), target.last());
    }

    @Test
    public void exceptionTraceActionReportsTheClassAndReason() {
        Term body = List.of(Tuple.of(Atom.of("exception_trace")));
        Term specification = List.of(Tuple.of(
            List.of(Atom.of("$1"), Atom.of("$2")), List.nil, body));
        Tracing.pattern(Tuple.of(signature.module(), signature.function(),
            signature.fun_arity()), specification, List.nil);

        assertNotNull(Tracing.beforeCallDecision(target, signature,
            List.of(Integer.of(1), Integer.of(2))));
        Tracing.afterCall(target, signature, null, new Error(Atom.of("boom")));

        assertEquals(Tuple.of(Tracing.TRACE, target.pid(), Tracing.EXCEPTION_FROM,
            Tuple.of(signature.module(), signature.function(), signature.fun_arity()),
            Tuple.of(Atom.of("error"), Atom.of("boom"))), target.last());
    }

    @Test
    public void silentActionSuppressesTheCallAndReturnEvents() {
        Term body = List.of(
            Tuple.of(Atom.of("message"), Atom.of("hidden")),
            Tuple.of(Atom.of("silent"), Boolean.am_true));
        Term specification = List.of(Tuple.of(
            List.of(Atom.of("$1"), Atom.of("$2")), List.nil, body));
        Tracing.pattern(Tuple.of(signature.module(), signature.function(),
            signature.fun_arity()), specification, List.nil);

        assertNotNull(Tracing.beforeCallDecision(target, signature,
            List.of(Integer.of(1), Integer.of(2))));
        assertNull(target.last());
        Tracing.afterReturn(target, signature, Integer.of(7));
        assertNull(target.last());
    }

    @Test
    @SuppressWarnings("deprecation")
    public void legacyPatternApiStillEmitsCallAndReturnTo() {
        Tracing.pattern(Tuple.of(signature.module(), signature.function(),
            signature.fun_arity()), Boolean.am_true,
            List.of(Atom.of("return_trace")));

        Tracing.Pattern pattern = Tracing.beforeCall(target, signature,
            List.of(Integer.of(1), Integer.of(2)));
        assertNotNull(pattern);
        assertEquals(Tuple.of(Tracing.TRACE, target.pid(), Tracing.CALL,
            Tuple.of(signature.module(), signature.function(),
                List.of(Integer.of(1), Integer.of(2)))), target.last());

        Tracing.afterCall(target, signature, pattern);
        assertEquals(Tuple.of(Tracing.TRACE, target.pid(), Tracing.RETURN_TO,
            Tuple.of(signature.module(), signature.function(),
                signature.fun_arity())), target.last());
    }

    private static final class RecordingProcess extends Process {

        private final java.util.List<Term> events = new ArrayList<>();
        private Term last;

        RecordingProcess(int number) {
            super(new PID(number));
        }

        @Override
        public void trace(Term event) {
            last = event;
            events.add(event);
        }

        Term last() {
            return last;
        }

    }

}
