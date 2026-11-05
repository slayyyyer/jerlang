package org.jerlang.api;

import java.time.Duration;

import org.jerlang.FunctionSignature;
import org.jerlang.Module;
import org.jerlang.ModuleRegistry;
import org.jerlang.jvm.JavaBridge;
import org.jerlang.type.Atom;
import org.jerlang.type.List;
import org.jerlang.type.Term;

/**
 * = Calling Erlang from Java
 *
 * The reverse direction of {@link org.jerlang.stdlib.Java}: plain Java code
 * invokes an exported Erlang function in the same VM.
 *
 * ----
 * Term result = Bridge.call("lists", "reverse", List.of(Integer.of(1), Integer.of(2)));
 * ----
 *
 * When called from a thread that is not an Erlang process, the VM spawns a
 * process, runs the function in it and waits for the result.
 */
public final class Bridge {

    private Bridge() {
    }

    public static Term call(String module, String function, Term... arguments) {
        return call(module, function, 0L, arguments);
    }

    /**
     * Call an exported Erlang function and bound how long the spawned process
     * may run. A timed-out call throws {@link Module.ApplyTimeoutException};
     * the process is killed first.
     *
     * <p>A timeout only applies to a call made from a Java thread that is not
     * itself an Erlang process. A callback already running inside Erlang can
     * not be interrupted this way, so it remains unbounded.
     */
    public static Term call(String module, String function, Duration timeout,
        Term... arguments) {
        return call(module, function, timeoutMillis(timeout), arguments);
    }

    private static Term call(String module, String function, long timeoutMillis,
        Term... arguments) {
        List args = List.of(arguments);
        Module target = ModuleRegistry.get(Atom.of(module));
        if (target == null) {
            throw new IllegalArgumentException("Unknown module: " + module);
        }
        FunctionSignature signature = new FunctionSignature(target, function, args.length());
        if (!target.hasFunction(signature)) {
            throw new IllegalArgumentException("Unknown function: "
                + module + ":" + function + "/" + args.length());
        }
        return target.apply(signature, args, timeoutMillis);
    }

    private static long timeoutMillis(Duration timeout) {
        if (timeout == null) {
            throw new IllegalArgumentException("timeout must not be null");
        }
        if (timeout.isNegative() || timeout.isZero()) {
            throw new IllegalArgumentException("timeout must be positive: " + timeout);
        }
        try {
            long millis = timeout.toMillis();
            if (millis < 1L) {
                throw new IllegalArgumentException("timeout must be at least 1 ms: " + timeout);
            }
            return millis;
        } catch (ArithmeticException e) {
            throw new IllegalArgumentException("timeout is too large: " + timeout, e);
        }
    }

    /**
     * Converts a Java value to an Erlang term.
     */
    public static Term toTerm(Object value) {
        return JavaBridge.toTerm(value);
    }

    /**
     * Converts an Erlang term to a Java value.
     */
    public static Object toJava(Term term) {
        return JavaBridge.toJava(term);
    }

}
