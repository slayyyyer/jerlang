package org.jerlang.compiler.scan;

import org.jerlang.type.Atom;
import org.jerlang.type.Integer;
import org.jerlang.type.List;
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
 *
 * [text] is the source substring the token came from. Nothing in the token
 * term shows it, but epp needs it for ??Arg stringification, where the answer
 * is the argument written exactly as the programmer wrote it, quotes and all.
 */
public final class Token {

    private final Atom category;
    private final int line;
    private final Term value;
    private final String text;

    Token(Atom category, int line, Term value) {
        this(category, line, value, null);
    }

    Token(Atom category, int line) {
        this(category, line, null, null);
    }

    Token(Atom category, int line, Term value, String text) {
        this.category = category;
        this.line = line;
        this.value = value;
        this.text = text;
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

    /**
     * The source text of the token, or a best effort reconstruction for the
     * tokens a preprocessor or a test made up rather than scanned.
     */
    public String text() {
        if (text != null) {
            return text;
        }
        if (value == null) {
            return category.toString();
        }
        if (category.equals(Atom.of("string"))) {
            return quote(stringValue());
        }
        return value.toString();
    }

    public Token withLine(int newLine) {
        return new Token(category, newLine, value, text);
    }

    public Term toTerm() {
        if (value == null) {
            return Tuple.of(category, Integer.of(line));
        }
        return Tuple.of(category, Integer.of(line), value);
    }

    public static Token of(Atom category, int line) {
        return new Token(category, line);
    }

    public static Token of(Atom category, int line, Term value) {
        return new Token(category, line, value);
    }

    public static Token string(String value, int line) {
        List codePoints = List.nil;
        for (int index = value.length(); index > 0;) {
            int codePoint = value.codePointBefore(index);
            index -= Character.charCount(codePoint);
            codePoints = new List(Integer.of(codePoint), codePoints);
        }
        return new Token(Atom.of("string"), line, codePoints);
    }

    public static Token eof(int line) {
        return new Token(Atom.of("eof"), line);
    }

    private String stringValue() {
        StringBuilder text = new StringBuilder();
        List list = value.toList();
        while (list != List.nil) {
            text.appendCodePoint(((Integer) list.head()).toInt());
            list = list.tail();
        }
        return text.toString();
    }

    private static String quote(String value) {
        StringBuilder text = new StringBuilder("\"");
        for (int index = 0; index < value.length();) {
            int codePoint = value.codePointAt(index);
            index += Character.charCount(codePoint);
            switch (codePoint) {
            case '"':
                text.append("\\\"");
                break;
            case '\\':
                text.append("\\\\");
                break;
            case '\n':
                text.append("\\n");
                break;
            case '\r':
                text.append("\\r");
                break;
            case '\t':
                text.append("\\t");
                break;
            default:
                text.appendCodePoint(codePoint);
            }
        }
        return text.append('"').toString();
    }

    @Override
    public String toString() {
        return toTerm().toString();
    }

}
