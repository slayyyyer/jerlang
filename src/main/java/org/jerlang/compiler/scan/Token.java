package org.jerlang.compiler.scan;

import org.jerlang.type.Atom;
import org.jerlang.type.Integer;
import org.jerlang.type.Term;
import org.jerlang.type.Tuple;

/**
 * = One token
 *
 * The shape is erl_scan's, deliberately: a category, the line the token starts
 * on, and a value for the categories that carry one. Keywords, operators and
 * punctuation are two element tuples, exactly as erl_scan emits them, so a
 * token stream can be compared with OTP's term for term instead of field by
 * field. That comparison is the scanner's oracle.
 *
 * The line annotation is the token's first line, not the line the scanner
 * finished on: a string or a quoted atom that spans lines is reported on the
 * line where its opening quote appeared, which is what a compile error needs
 * and what erl_scan records.
 */
public final class Token {

    private final Atom category;
    private final int line;
    private final Term value;

    Token(Atom category, int line, Term value) {
        this.category = category;
        this.line = line;
        this.value = value;
    }

    Token(Atom category, int line) {
        this(category, line, null);
    }

    public Atom category() {
        return category;
    }

    public int line() {
        return line;
    }

    public Term value() {
        return value;
    }

    public boolean hasValue() {
        return value != null;
    }

    public Term toTerm() {
        if (value == null) {
            return Tuple.of(category, Integer.of(line));
        }
        return Tuple.of(category, Integer.of(line), value);
    }

    @Override
    public String toString() {
        return toTerm().toString();
    }

}
