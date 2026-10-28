package org.jerlang.erts.emulator.op;

import org.jerlang.FunctionSignature;
import org.jerlang.Module;
import org.jerlang.Process;
import org.jerlang.erts.emulator.Instruction;
import org.jerlang.stdlib.beam_lib.LambdaInfo;
import org.jerlang.type.Fun;
import org.jerlang.type.Integer;
import org.jerlang.type.List;
import org.jerlang.type.Term;

/**
 * Create a fun and store it in Destination.
 *
 * Introduced in OTP 24. The first argument is an index into the lambda table,
 * which provides the function's label and free-variable count; the free
 * variables themselves are listed explicitly, so they can live in Y registers
 * rather than only in X0 to X(NumFree-1).
 *
 * Arguments:
 * 1. Lambda index
 * 2. Destination
 * 3. Free variables
 *
 * Example:
 * {make_fun3,0,{x,0},{list,[{x,0}]}}
 */
public class MakeFun3 {

    public static Term execute(Process proc, Module m, Instruction i, List params) {
        Integer index = i.arg(0).toInteger();
        Term destination = i.arg(1);
        List freeVariables = i.arg(2).toList();

        LambdaInfo lambda = m.beamData().functionTableChunk().lambdas().get(index.toInt());
        FunctionSignature signature = lambda.toFunctionSignature(m.name());

        Term[] saved = new Term[freeVariables.length()];
        int position = 0;
        while (freeVariables.length() > 0) {
            saved[position++] = Swap.get(proc, freeVariables.head());
            freeVariables = freeVariables.tail();
        }

        Fun fun = new Fun(m, signature, lambda.label().toInt(), saved);
        Swap.set(proc, destination, fun);
        return null;
    }

}
