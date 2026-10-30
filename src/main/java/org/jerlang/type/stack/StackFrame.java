package org.jerlang.type.stack;

import org.jerlang.type.Atom;
import org.jerlang.type.Integer;
import org.jerlang.type.List;
import org.jerlang.type.Str;
import org.jerlang.type.Term;
import org.jerlang.type.Tuple;

/**
 * One activation of an Erlang function.
 *
 * A stacktrace is a list of these, youngest call first, where each frame is
 * a tuple {Module, Function, Arity, Location}. The youngest frame of an error
 * raised by erlang:error/2,3 carries the caller's argument list in place of
 * the arity. Location is either an empty list (no line information) or
 * [{file, Filename}, {line, LineNumber}].
 */
public class StackFrame {

    private final Atom module;
    private final Atom function;
    private final int arity;

    // The argument list an error/2 or error/3 frame carries in place of its
    // arity, or null for an ordinary frame.
    private final List args;

    private String filename;
    private int line;

    public StackFrame(Atom module, Atom function, int arity) {
        this(module, function, arity, null);
    }

    private StackFrame(Atom module, Atom function, int arity, List args) {
        this.module = module;
        this.function = function;
        this.arity = arity;
        this.args = args;
    }

    /**
     * A copy of this frame that reports the given argument list instead of
     * the arity, which is what erlang:error/2,3 puts at the head of the
     * stacktrace it raises.
     */
    public StackFrame withArgs(List args) {
        StackFrame frame = new StackFrame(module, function, arity, args);
        frame.filename = filename;
        frame.line = line;
        return frame;
    }

    public void line(String filename, int line) {
        this.filename = filename;
        this.line = line;
    }

    public Atom module() {
        return module;
    }

    public Atom func() {
        return function;
    }

    public int arity() {
        return arity;
    }

    public String filename() {
        return filename;
    }

    public int lineNumber() {
        return line;
    }

    public Term toTerm() {
        Term location = List.nil;
        if (filename != null && line > 0) {
            location = List.of(
                Tuple.of(Atom.of("file"), Str.of(filename)),
                Tuple.of(Atom.of("line"), Integer.of(line)));
        }
        Term third = args == null ? Integer.of(arity) : args;
        return Tuple.of(module, function, third, location);
    }

    @Override
    public String toString() {
        return toTerm().toString();
    }

}
