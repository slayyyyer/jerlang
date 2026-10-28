package org.jerlang.api;

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
        return target.apply(signature, args);
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
