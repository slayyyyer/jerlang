package org.jerlang;

import java.util.Map;

import org.jerlang.erts.emulator.Instruction;
import org.jerlang.erts.emulator.op.Line;
import org.jerlang.erts.erlang.Error;
import org.jerlang.stdlib.beam_lib.BeamData;
import org.jerlang.stdlib.beam_lib.CodeChunk;
import org.jerlang.type.Atom;
import org.jerlang.type.List;
import org.jerlang.type.Str;
import org.jerlang.type.Term;
import org.jerlang.type.stack.ExceptionHandler;
import org.jerlang.type.stack.StackFrame;

/**
 * Executes opcodes
 */
public class Interpreter {

    private static final Atom LINE = Atom.of("line");
    private static final boolean DEBUG_ERRORS = Boolean.getBoolean("jerlang.debug.errors");

    public static Term dispatch(List params) {
        // A call to a function defined in a BEAM file
        // has always the signature as first parameter.

        Process process = (Process) ProcessRegistry.self();
        FunctionSignature s = process.signature();

        params_to_register(params, process.registers());

        Module m = ModuleRegistry.get(s.module());
        int start = find_start(s, m.beamData());

        return continueExecution(s, start);
    }

    public static Term continueExecution(FunctionSignature signature, int start) {
        Process process = (Process) ProcessRegistry.self();

        Module m = ModuleRegistry.get(signature.module());

        if (m.isInternal()) {
            // We need to fetch params from registers
            List args = List.nil;
            for (int index = signature.fun_arity().toInt(); index > 0; index--) {
                args = new List(process.getX(index - 1), args);
            }
            Term res = m.apply(signature, args);
            process.setX(0, res);
            // Setting the EXITING state must be the last
            // operation before return. Otherwise another
            // thread waiting for this process might get
            // wrong result as X0 is not filled yet.
            process.setState(ProcessState.EXITING);
            return res;
        }

        if (start == -1) {
            start = find_start(signature, m.beamData());
        }

        return continueExecution(m, start);
    }

    /**
     * Run the instruction stream of a module from the given index.
     *
     * The index belongs to the module's code, which is what lets a resumed
     * activation, or a call from a different module, start here without a
     * signature to look the module up.
     */
    public static Term continueExecution(Module m, int start) {
        Process process = (Process) ProcessRegistry.self();
        return continueExecution(m, start, process.enterActivation());
    }

    /**
     * Run an instruction stream as the given activation. A resumed receive
     * loop passes the identifier it saved when it parked, so the handlers it
     * installed are still in scope.
     */
    public static Term continueExecution(Module m, int start, long activation) {
        Process process = (Process) ProcessRegistry.self();
        process.enterInterpreterFrame();
        try {
            return runUntilDone(process, m, start, activation);
        } finally {
            process.exitInterpreterFrame();
            process.exitActivation();
        }
    }

    /**
     * Re-enter an activation that was preempted. Its Java frame was popped
     * when it yielded, so the activation identifier has to be pushed again
     * before its instruction stream continues.
     */
    public static Term resumeExecution(Module m, int start, long activation) {
        Process process = (Process) ProcessRegistry.self();
        process.enterActivation(activation);
        process.enterInterpreterFrame();
        try {
            return runUntilDone(process, m, start, activation);
        } finally {
            process.exitInterpreterFrame();
            process.exitActivation();
        }
    }

    private static Term runUntilDone(Process process, Module m, int start, long activation) {
        CodeChunk code = m.beamData().codeChunk();
        java.util.List<Instruction> instructions = code.instructions();
        Map<Integer, Integer> labels = code.labels();
        int maxInstructions = instructions.size();

        Term result = List.nil;
        Term params = List.nil; // TODO: is this correct?

        for (int index = start; index < maxInstructions; index++) {
            if (process.reductionSliceExhausted()
                && process.interpreterFrames() == 1
                && !process.hasResumePoints()) {
                // The scheduler gave this activation a budget. Stop at an
                // instruction boundary so another process can run; execute()
                // resumes here when the scheduler picks this process again.
                process.yieldActivation(m, index, activation);
                break;
            }
            process.spendReduction();
            if (process.hasPendingExit()) {
                // An exit signal is not an Erlang exception: it unwinds all
                // the way out of the process instead of into a catch clause.
                throw new ProcessExit();
            }
            Instruction i = instructions.get(index);

            // process.printStack();
            // System.out.println(i);

            if (LINE.equals(i.element(1))) {
                Line.execute(process, m, i, params.toList());
                continue;
            }

            try {

                if (i.opcode().methodHandle() != null) {
                    Term r = (Term) i.opcode().methodHandle().invoke(process, m, i, params);
                    if (r != null) {
                        if (r instanceof org.jerlang.type.Integer) {
                            // CP
                            index = r.toInteger().toInt();
                            if (index == 0) {
                                // Return
                            }
                        } else {
                            // this must be a label jump (e.g. "{f,1}")
                            int lbl = r.toRegisterIndex().toInt();
                            if (code.isFunctionEntryLabel(lbl)) {
                                // A failed clause test jumps back to the
                                // function entry. BEAM turns that into
                                // function_clause instead of re-entering.
                                throw new Error(Atom.of("function_clause"));
                            }
                            index = labels.get(lbl);
                        }
                    }
                } else {
                    StackFrame frame = process.currentFrame();
                    String where = m.name().toString();
                    if (frame != null) {
                        where += ":" + frame.func() + "/" + frame.arity();
                        if (frame.lineNumber() > 0) {
                            where += " at " + frame.lineNumber();
                        }
                    }
                    throw new Error(Str.of("Unsupported opcode " + i.opcode()
                        + " in " + where));
                }
            } catch (Error error) {
                // Capture the stack at the raise, before the handler unwinds it.
                error.captureStacktrace(process);
                ExceptionHandler handler = process.exceptionHandler();
                if (handler != null && handler.activation() == activation) {
                    // System.err.println(process.exceptionHandler());
                    int new_index = handler.index();
                    if (new_index >= 0) {
                        index = new_index;
                    }
                    // handle() puts class, reason and stacktrace in X0..X2
                    // and restores the enclosing handler.
                    handler.handle(process, error);
                } else {
                    if (DEBUG_ERRORS) {
                        System.err.println("RAISE " + m.name() + ":" + process.currentFrame()
                            + " at " + i + " " + error.exceptionClass() + ":" + error.reason()
                            + " handler=" + handler + " activation=" + activation);
                    }
                    // A handler of another activation, or none: let the
                    // exception unwind the Java frames until it reaches the
                    // instruction stream that installed it. The scheduler
                    // turns an exception nobody caught into the process's
                    // exit reason.
                    throw error;
                }
            } catch (Throwable e) {
                if (e instanceof ProcessExit) {
                    throw (ProcessExit) e;
                }
                if (e instanceof RuntimeException) {
                    throw (RuntimeException) e;
                }
                throw new Error(Str.of(String.valueOf(e)));
            }

            if (process.state() == ProcessState.WAITING) {
                // Set process was set to waiting mode.
                process.setCP(index);
                break;
            }

            Opcode opcode = i.opcode();

            // A tail call or a return jumps to the continuation pointer of
            // the calling function. Without one (index 0) the process ends.
            // apply_last tears down its own frame, so it always ends here.
            if (opcode == Opcode.apply_last
                || ((opcode == Opcode.call_ext_last
                    || opcode == Opcode.call_ext_only
                    || opcode == Opcode._return) && index <= 0)) {
                // The result of the call or return
                // is stored in the x0 register
                result = process.registers()[0];
                process.setState(ProcessState.EXITING);
                break;
            }
        }
        return result;
    }

    /**
     * Resume execution at a label such as `{f,3}`. Used when a suspended
     * receive loop is woken by a message.
     */
    public static Term continueExecutionAtLabel(FunctionSignature signature, Term label) {
        return continueExecutionAtLabel(ModuleRegistry.get(signature.module()), label);
    }

    /**
     * Resume a module's instruction stream at a label inside it.
     */
    public static Term continueExecutionAtLabel(Module m, Term label) {
        return continueExecutionAtLabel(m, label, 0);
    }

    /**
     * Resume a module's instruction stream at a label inside it, as the
     * activation that installed its handlers before it parked.
     */
    public static Term continueExecutionAtLabel(Module m, Term label, long activation) {
        Integer index = m.beamData().codeChunk().labels().get(label.toRegisterIndex().toInt());
        if (index == null) {
            throw new Error("No instruction for label " + label + " in " + m.name());
        }
        if (activation == 0) {
            return continueExecution(m, index);
        }
        return continueExecution(m, index, activation);
    }

    /**
     * Store the params, if any, in the X registers of the process.
     */
    private static void params_to_register(List params, Term[] registers) {
        int index = 0;
        while (params.length() > 0) {
            registers[index++] = params.head();
            params = params.tail();
        }
    }

    /**
     * Looks for the exported functions.
     * On match, returns the index of the label instruction
     * that is assigned to the function.
     */
    private static int find_start(FunctionSignature s, BeamData beamData) {
        for (FunctionSignature fs : beamData.exportTableChunk().exports()) {
            if (fs.equals(s)) {
                int lbl = fs.label().toInt();
                int idx = beamData.codeChunk().labels().get(lbl);
                return idx;
            }
        }
        throw new Error("No label found for " + s);
    }
}
