package org.jerlang.erts.emulator.op;

import org.jerlang.Module;
import org.jerlang.Process;
import org.jerlang.erts.emulator.Instruction;
import org.jerlang.type.List;
import org.jerlang.type.Term;

/**
 * Ensure that the process heap has room for a fixed number of words. The JVM
 * allocates terms on its own heap, so this instruction only exists to keep
 * generated BEAM streams executable by the interpreter.
 *
 * Arguments:
 * 1. Heap words needed
 * 2. Live X registers
 *
 * Example:
 * {test_heap,3,2}
 */
public class TestHeap {

    public static Term execute(Process proc, Module m, Instruction i, List params) {
        return null;
    }

}
