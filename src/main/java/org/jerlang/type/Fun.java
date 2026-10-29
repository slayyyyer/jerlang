package org.jerlang.type;

import java.lang.invoke.MethodHandle;

import org.jerlang.FunctionSignature;
import org.jerlang.Module;
import org.jerlang.erts.erlang.Error;
import org.jerlang.stdlib.beam_lib.LambdaInfo;

/**
 * http://erlang.org/doc/programming_examples/funs.html
 */
public class Fun extends Term {

    private final FunctionSignature signature;
    private final MethodHandle handle;

    private Module module;
    private LambdaInfo lambdaInfo;
    private PID owner;
    private int explicitLabel = -1;
    private Term[] saved_registers = {};

    public Fun(org.jerlang.Process proc, Module module, LambdaInfo lambdaInfo) {
        this(proc, module, lambdaInfo, null);
    }

    /**
     * A fun created by make_fun2 or make_fun3. The owning process and the
     * lambda table entry are kept so the fun can be encoded in NEW_FUN_EXT.
     */
    public Fun(org.jerlang.Process proc, Module module, LambdaInfo lambdaInfo,
        Term[] savedRegisters) {
        this(lambdaInfo.toFunctionSignature(module.name()), null);
        this.module = module;
        this.lambdaInfo = lambdaInfo;
        this.owner = proc == null ? null : proc.pid();
        this.explicitLabel = lambdaInfo.label().toInt();
        if (savedRegisters != null) {
            this.saved_registers = savedRegisters;
        } else {
            int numFree = lambdaInfo.numFree();
            if (numFree > 0) {
                saved_registers = new Term[numFree];
                System.arraycopy(proc.registers(), 0, saved_registers, 0, numFree);
            }
        }
    }

    public Fun(Module module, FunctionSignature signature, int label, Term[] savedRegisters) {
        this(signature, null);
        this.module = module;
        this.explicitLabel = label;
        this.saved_registers = savedRegisters;
    }

    /**
     * Rebuild an internal fun from NEW_FUN_EXT/FUN_EXT. The lambda table
     * entry supplies the label and free-variable count.
     */
    public Fun(Module module, LambdaInfo lambdaInfo, PID owner, Term[] savedRegisters) {
        this(lambdaInfo.toFunctionSignature(module.name()), null);
        this.module = module;
        this.lambdaInfo = lambdaInfo;
        this.owner = owner;
        this.explicitLabel = lambdaInfo.label().toInt();
        this.saved_registers = savedRegisters == null ? new Term[0] : savedRegisters;
    }

    public Fun(FunctionSignature signature, MethodHandle handle) {
        this.signature = signature;
        this.handle = handle;
    }

    public Term apply(Term param) throws Error {
        try {
            return (Term) handle.invoke(param);
        } catch (Error e) {
            throw e;
        } catch (Throwable e) {
            e.printStackTrace();
            throw new Error("Could not apply " + param + " to " + signature);
        }
    }

    public MethodHandle handle() {
        return handle;
    }

    public Term[] savedRegisters() {
        return saved_registers;
    }

    public FunctionSignature signature() {
        return signature;
    }

    public Module module() {
        return module;
    }

    public LambdaInfo lambdaInfo() {
        return lambdaInfo;
    }

    /** The arity callers see, excluding the captured free variables. */
    public int arity() {
        return signature.fun_arity().toInt() - saved_registers.length;
    }

    public PID owner() {
        return owner;
    }

    /** Whether this is an internal lambda with a lambda table entry. */
    public boolean isInternal() {
        return lambdaInfo != null;
    }

    public Fun toFun() {
        return this;
    }

    @Override
    public String toString() {
        return "{Fun," + signature.toString() + "}";
    }

    public Term label() {
        int label = explicitLabel >= 0 ? explicitLabel : lambdaInfo.label().toInt();
        return Tuple.of(Atom.of("label"), Integer.of(label));
    }

}
