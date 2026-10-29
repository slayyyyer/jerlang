package org.jerlang.erts.emulator.op;

import org.jerlang.Module;
import org.jerlang.Process;
import org.jerlang.erts.emulator.Instruction;
import org.jerlang.type.List;
import org.jerlang.type.Term;

/**
 * Get the head and tail (or car and cdr) parts of a list
 * (a cons cell) from Source and put them into the registers
 * Head and Tail.
 *
 * Arguments:
 * 1. Source
 * 2. Head
 * 3. Tail
 */
public class GetList {

    public static Term execute(Process proc, Module m, Instruction i, List params) {
        List list = Swap.get(proc, i.arg(0)).toList();
        // Head and tail can be in either register bank. Writing both to X
        // silently overwrites the head when the tail is a Y register, which
        // is how list comprehensions are compiled.
        Swap.set(proc, i.arg(1), list.head());
        Swap.set(proc, i.arg(2), list.tail());
        return null;
    }

}
