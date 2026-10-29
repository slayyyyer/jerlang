package org.jerlang;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Map;

import org.jerlang.erts.erlang.Error;
import org.jerlang.stdlib.beam_lib.BeamData;
import org.jerlang.stdlib.beam_lib.BeamLibMD5;
import org.jerlang.stdlib.beam_lib.LambdaInfo;
import org.jerlang.type.Atom;
import org.jerlang.type.Binary;
import org.jerlang.type.Fun;
import org.jerlang.type.Integer;
import org.jerlang.type.List;
import org.jerlang.type.Str;
import org.jerlang.type.Term;
import org.jerlang.type.Tuple;
import org.jerlang.type.stack.StackFrame;
import org.jerlang.util.StringUtil;
import org.jerlang.vm.VirtualMachine;

/**
 * = Modules
 *
 * Erlang code is divided into modules.
 * A module consists of a sequence of attributes and function declarations,
 * each terminated by period (.).
 *
 * Example:
 * ----
 * -module(m).          % module attribute
 * -export([fact/1]).   % module attribute
 *
 * fact(N) when N>0 ->  % beginning of function declaration
 *     N * fact(N-1);   %  |
 * fact(0) ->           %  |
 *     1.               % end of function declaration
 * ----
 *
 * http://www.erlang.org/doc/reference_manual/modules.html
 */

public class Module {

    private static final MethodType METHOD_TYPE = MethodType.methodType(Term.class, List.class);
    private static final MethodType NAME_METHOD = MethodType.methodType(Atom.class);

    private final BeamData beamData;
    private final Class<?> moduleClass;
    private final Atom name;
    private final Map<FunctionSignature, Fun> exported_functions;
    private Map<java.lang.Integer, FunctionSignature> functionsByLabel;
    private final Map<FunctionSignature, Integer> labels;
    private byte[] md5;

    public Module(BeamData beamData, Atom name) {
        this.beamData = beamData;
        this.exported_functions = new HashMap<>();
        this.labels = new HashMap<>();
        this.moduleClass = null;
        this.name = name;
    }

    public Module(Class<?> moduleClass, Atom name) {
        this.beamData = null;
        this.exported_functions = new HashMap<>();
        this.labels = new HashMap<>();
        this.moduleClass = moduleClass;
        this.name = name;
    }

    public Term apply(FunctionSignature signature, Term params) throws Error {
        if (!hasFunction(signature)) {
            throw new Error("Unknown function: " + signature);
        }
        Process process = VirtualMachine.self();
        if (process == null) {
            // We have been probably invoked from plain Java code,
            // So we need to spawn a process and execute the function
            // within that process. The calling thread must not keep the
            // spawned process as its "self": a later call would otherwise
            // run in the finished process instead of spawning a new one.
            ProcessOrPort previous = ProcessRegistry.self();
            process = VirtualMachine.instance().spawn(signature.module(), signature.function(), params.toList());
            ProcessRegistry.self(process);
            try {
                while (process.state() != ProcessState.EXITING) {
                    try {
                        Thread.sleep(10);
                    } catch (InterruptedException e) {
                        e.printStackTrace();
                    }
                }
                Throwable failure = process.failure();
                if (failure != null) {
                    if (failure instanceof Error) {
                        throw (Error) failure;
                    }
                    if (failure instanceof RuntimeException) {
                        throw (RuntimeException) failure;
                    }
                    throw new Error(Str.of(String.valueOf(failure)));
                }
                return process.getX(0);
            } finally {
                ProcessRegistry.self(previous);
            }
        } else {
            process.pushSignature(signature);
            if (!isInternal()) {
                process.pushFrame(new StackFrame(signature.module(), signature.function(),
                    signature.fun_arity().toInt()));
            }
            Fun fun = exported_functions.get(signature);
            process.enterApply();
            try {
                return fun.apply(params);
            } finally {
                process.leaveApply();
                process.popSignature();
            }
        }
    }

    public BeamData beamData() {
        return beamData;
    }

    /**
     * The 16 byte BeamLibMD5 value that NEW_FUN_EXT uses to identify the
     * module version. A module compiled from an in-memory or Java class
     * has no BEAM file, so its funs use a zero digest.
     */
    public synchronized byte[] md5() {
        if (md5 != null) {
            return md5;
        }
        md5 = new byte[16];
        if (beamData == null) {
            return md5;
        }
        try {
            Term result = BeamLibMD5.md5_1(Str.of(name.toString() + ".beam"));
            if (result instanceof Tuple && ((Tuple) result).arity() == 2
                && Atom.of("ok").equals(((Tuple) result).element(1))) {
                Tuple info = ((Tuple) result).element(2).toTuple();
                if (info.arity() == 2 && info.element(2) instanceof Binary) {
                    md5 = ((Binary) info.element(2)).toByteArray();
                }
            }
        } catch (Throwable e) {
            // A missing or unreadable file leaves the zero digest. Funs
            // still work locally and between JErlang nodes.
        }
        return md5;
    }

    /**
     * The base name of the source file this module was compiled from, as
     * recorded in the compile info chunk, or null if the module has none.
     */
    public Str sourceName() {
        if (beamData == null || beamData.compileInfoChunk() == null) {
            return null;
        }
        List info = beamData.compileInfoChunk().compile_info();
        while (info.length() > 0) {
            Term entry = info.head();
            if (entry instanceof Tuple && ((Tuple) entry).arity() == 2
                && ((Tuple) entry).element(1).equals(Atom.of("source"))) {
                String path = ((Tuple) entry).element(2).toStr().string();
                int separator = Math.max(path.lastIndexOf('/'), path.lastIndexOf('\\'));
                return Str.of(separator >= 0 ? path.substring(separator + 1) : path);
            }
            info = info.tail();
        }
        return null;
    }

    /**
     * The function whose body starts at the given label, or null if no
     * function starts there.
     */
    public FunctionSignature functionAtLabel(int label) {
        if (beamData == null) {
            return null;
        }
        if (functionsByLabel == null) {
            functionsByLabel = new HashMap<>();
            for (FunctionSignature s : beamData.exportTableChunk().exports()) {
                functionsByLabel.put(s.label().toInt(), s);
            }
            if (beamData.localFunctionTableChunk() != null) {
                List locals = beamData.localFunctionTableChunk().locals();
                while (locals.length() > 0) {
                    Term head = locals.head();
                    if (head instanceof FunctionSignature) {
                        FunctionSignature s = (FunctionSignature) head;
                        functionsByLabel.putIfAbsent(s.label().toInt(), s);
                    }
                    locals = locals.tail();
                }
            }
            if (beamData.functionTableChunk() != null) {
                for (LambdaInfo lambda : beamData.functionTableChunk().lambdas()) {
                    functionsByLabel.putIfAbsent(lambda.label().toInt(),
                        lambda.toFunctionSignature(name));
                }
            }
        }
        return functionsByLabel.get(label);
    }

    public void export() {
        if (moduleClass == null) {
            // The module was loaded from a BEAM file
            MethodHandle mh = null;
            try {
                mh = MethodHandles.lookup().findStatic(Interpreter.class, "dispatch", METHOD_TYPE);
            } catch (Exception e) {
                e.printStackTrace();
            }
            for (FunctionSignature s : beamData.exportTableChunk().exports()) {
                labels.put(s, s.label());
                exported_functions.put(s, new Fun(s, mh));
            }
        } else {
            // The module is included in JErlang
            for (Method method : moduleClass.getDeclaredMethods()) {
                int arity = method.getParameterCount();
                if (method.getName().startsWith("_")) {
                    // If the method name is a reserved Java keyword,
                    // the method is prefixed by an underscore.
                    // For example: `erlang:throw` or `ets:new`
                    export(method.getName().substring(1), arity);
                } else {
                    export(method.getName(), arity);
                }
            }
        }
    }

    public Module export(String name, int arity) {
        return export(Atom.of(name), Integer.of(arity));
    }

    public Module export(Atom name, Integer arity) {
        FunctionSignature s = new FunctionSignature(
            this.name, name, arity);
        try {
            String p = moduleClass.getPackage().getName();
            String pm = p + "." + this.name.toString();
            String cn = pm + "."
                + StringUtil.snakeToCamelCase(this.name.toString())
                + StringUtil.snakeToCamelCase(name.toString());
            Class<?> c = getClass().getClassLoader().loadClass(cn);
            MethodHandle handle = MethodHandles.lookup()
                .findStatic(c, "dispatch", METHOD_TYPE);
            s = maybe_override_name(s, c);
            exported_functions.put(s, new Fun(s, handle));
        } catch (NoSuchMethodException | IllegalAccessException | ClassNotFoundException e) {
            System.err.println("Can not export: " + s);
        }
        return this;
    }

    /**
     * This module is included in JErlang and
     * not loaded from a BEAM file.
     */
    public boolean isInternal() {
        return (moduleClass != null) && (beamData == null);
    }

    /**
     * Some functions have names that cannot be expressed in Java.
     * For example: "erlang:+/2".
     * If a function implementing class declares a static name() method,
     * its return value will be used as function name instead.
     */
    private FunctionSignature maybe_override_name(FunctionSignature s, Class<?> c) {
        try {
            MethodHandle h = MethodHandles.lookup().findStatic(c, "name", NAME_METHOD);
            Atom newName = (Atom) h.invoke();
            s = new FunctionSignature(s.module(), newName, s.element(3).toInteger());
        } catch (Throwable e) {
            // Ignore
        }
        return s;
    }

    public boolean hasFunction(FunctionSignature functionSignature) {
        return exported_functions.containsKey(functionSignature);
    }

    public Atom name() {
        return name;
    }

}
