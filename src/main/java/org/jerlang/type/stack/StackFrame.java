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
 * a tuple {Module, Function, Arity, Location}. Location is either an empty
 * list (no line information) or [{file, Filename}, {line, LineNumber}].
 */
public class StackFrame {

    private final Atom module;
    private final Atom function;
    private final int arity;

    private String filename;
    private int line;

    public StackFrame(Atom module, Atom function, int arity) {
        this.module = module;
        this.function = function;
        this.arity = arity;
    }

    public void line(String filename, int line) {
        this.filename = filename;
        this.line = line;
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
        return Tuple.of(module, function, Integer.of(arity), location);
    }

    @Override
    public String toString() {
        return toTerm().toString();
    }

}
