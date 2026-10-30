package org.jerlang.kernel.file;

import java.io.ByteArrayOutputStream;
import java.math.BigInteger;
import java.util.ArrayList;

import org.jerlang.type.Atom;
import org.jerlang.type.Binary;
import org.jerlang.type.Float;
import org.jerlang.type.Integer;
import org.jerlang.type.List;
import org.jerlang.type.Map;
import org.jerlang.type.Str;
import org.jerlang.type.Term;
import org.jerlang.type.Tuple;

/**
 * Reads the terms in a file of the form file:consult/1 expects: whitespace
 * between terms, comments starting with %, a full stop after every term.
 *
 * This is the subset of the Erlang term syntax that configuration and .app
 * files use - atoms, numbers, strings, tuples, lists, binaries and maps - not
 * the full grammar of erl_parse. An expression or a call is a syntax error
 * here, as it is in consult: a term file does not evaluate anything.
 */
final class TermReader {

    static final class SyntaxError extends Exception {

        private final int line;

        SyntaxError(int line, String message) {
            super(message);
            this.line = line;
        }

        int line() {
            return line;
        }

    }

    private final String text;
    private int position;
    private int line = 1;

    private TermReader(String text) {
        this.text = text;
    }

    /**
     * Every term in the text, in file order.
     */
    static List parseAll(String text) throws SyntaxError {
        TermReader reader = new TermReader(text);
        ArrayList<Term> terms = new ArrayList<>();
        while (true) {
            reader.skipBlanks();
            if (reader.atEnd()) {
                break;
            }
            terms.add(reader.term());
            reader.skipBlanks();
            reader.expect('.');
        }
        List result = List.nil;
        for (int index = terms.size() - 1; index >= 0; index--) {
            result = new List(terms.get(index), result);
        }
        return result;
    }

    private Term term() throws SyntaxError {
        if (atEnd()) {
            throw error("unexpected end of file");
        }
        char current = peek();
        if (current == '{') {
            return tuple();
        }
        if (current == '[') {
            return list();
        }
        if (current == '#') {
            return mapOrError();
        }
        if (current == '<' && position + 1 < text.length() && text.charAt(position + 1) == '<') {
            return binary();
        }
        if (current == '"') {
            return Str.of(string());
        }
        if (current == '\'') {
            return Atom.of(quoted());
        }
        if (current == '$') {
            return character();
        }
        if (current == '-' || current == '+' || Character.isDigit(current)) {
            return number();
        }
        if (Character.isLowerCase(current)) {
            return Atom.of(unquoted());
        }
        throw error("syntax error");
    }

    private Tuple tuple() throws SyntaxError {
        expect('{');
        ArrayList<Term> elements = new ArrayList<>();
        skipBlanks();
        if (peek() == '}') {
            next();
            return Tuple.of();
        }
        while (true) {
            elements.add(term());
            skipBlanks();
            if (peek() == ',') {
                next();
                skipBlanks();
                continue;
            }
            expect('}');
            return Tuple.of(elements.toArray(new Term[0]));
        }
    }

    private List list() throws SyntaxError {
        expect('[');
        ArrayList<Term> elements = new ArrayList<>();
        skipBlanks();
        if (peek() == ']') {
            next();
            return List.nil;
        }
        while (true) {
            elements.add(term());
            skipBlanks();
            if (peek() == ',') {
                next();
                skipBlanks();
                continue;
            }
            List tail = List.nil;
            if (peek() == '|') {
                next();
                skipBlanks();
                tail = term().toList();
                skipBlanks();
            }
            expect(']');
            for (int index = elements.size() - 1; index >= 0; index--) {
                tail = new List(elements.get(index), tail);
            }
            return tail;
        }
    }

    private Binary binary() throws SyntaxError {
        expect('<');
        expect('<');
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        skipBlanks();
        if (peek() == '>') {
            expect('>');
            expect('>');
            return Binary.of(bytes.toByteArray());
        }
        while (true) {
            Term segment = term();
            if (segment instanceof Integer) {
                int value = ((Integer) segment).toInt();
                if (value < 0 || value > 255) {
                    throw error("bad binary element");
                }
                bytes.write(value);
            } else if (segment instanceof Str) {
                byte[] string = ((Str) segment).string()
                    .getBytes(java.nio.charset.StandardCharsets.UTF_8);
                bytes.write(string, 0, string.length);
            } else if (segment instanceof Binary) {
                byte[] binary = ((Binary) segment).toByteArray();
                bytes.write(binary, 0, binary.length);
            } else {
                throw error("bad binary element");
            }
            skipBlanks();
            if (peek() == ':') {
                // A size modifier, as in <<Size:8>>, needs the bit syntax
                // evaluator. Term files do not use it.
                throw error("unsupported binary segment");
            }
            if (peek() == ',') {
                next();
                skipBlanks();
                continue;
            }
            expect('>');
            expect('>');
            return Binary.of(bytes.toByteArray());
        }
    }

    private Map mapOrError() throws SyntaxError {
        expect('#');
        expect('{');
        Map map = new Map();
        skipBlanks();
        if (peek() == '}') {
            next();
            return map;
        }
        while (true) {
            Term key = term();
            skipBlanks();
            expect('=');
            expect('>');
            skipBlanks();
            map.set(key, term());
            skipBlanks();
            if (peek() == ',') {
                next();
                skipBlanks();
                continue;
            }
            expect('}');
            return map;
        }
    }

    private String string() throws SyntaxError {
        expect('"');
        StringBuilder value = new StringBuilder();
        while (true) {
            if (atEnd()) {
                throw error("unterminated string");
            }
            char current = peek();
            if (current == '"') {
                next();
                return value.toString();
            }
            if (current == '\\') {
                value.append(escape());
                continue;
            }
            value.append(next());
        }
    }

    private String quoted() throws SyntaxError {
        expect('\'');
        StringBuilder value = new StringBuilder();
        while (true) {
            if (atEnd()) {
                throw error("unterminated atom");
            }
            char current = peek();
            if (current == '\'') {
                next();
                return value.toString();
            }
            if (current == '\\') {
                value.append(escape());
                continue;
            }
            value.append(next());
        }
    }

    private String unquoted() {
        StringBuilder value = new StringBuilder();
        while (!atEnd()) {
            char current = peek();
            if (Character.isLetterOrDigit(current) || current == '_' || current == '@') {
                value.append(next());
            } else {
                break;
            }
        }
        return value.toString();
    }

    private Integer character() throws SyntaxError {
        expect('$');
        if (atEnd()) {
            throw error("truncated character");
        }
        if (peek() == '\\') {
            String value = escape();
            if (value.isEmpty()) {
                throw error("truncated character");
            }
            return Integer.of(value.codePointAt(0));
        }
        return Integer.of(next());
    }

    /**
     * A character escape, starting at the backslash. Returns the text the
     * escape stands for, so a string can append it and $x can take its first
     * code point.
     */
    private String escape() throws SyntaxError {
        expect('\\');
        if (atEnd()) {
            throw error("truncated escape");
        }
        char current = next();
        switch (current) {
        case 'n':
            return "\n";
        case 'r':
            return "\r";
        case 't':
            return "\t";
        case 'b':
            return "\b";
        case 'f':
            return "\f";
        case 'v':
            return "\u000B";
        case 'e':
            return "\u001B";
        case 's':
            return " ";
        case 'd':
            return "\u007F";
        case '\\':
        case '"':
        case '\'':
            return String.valueOf(current);
        case 'x':
            return new String(Character.toChars(hexCode()));
        default:
            break;
        }
        if (current >= '0' && current <= '7') {
            int value = current - '0';
            for (int digits = 1; digits < 3 && !atEnd() && peek() >= '0' && peek() <= '7'; digits++) {
                value = value * 8 + (next() - '0');
            }
            return String.valueOf((char) value);
        }
        throw error("unknown escape");
    }

    private int hexCode() throws SyntaxError {
        if (!atEnd() && peek() == '{') {
            next();
            StringBuilder digits = new StringBuilder();
            while (!atEnd() && peek() != '}') {
                digits.append(next());
            }
            expect('}');
            try {
                return java.lang.Integer.parseInt(digits.toString(), 16);
            } catch (NumberFormatException e) {
                throw error("bad hexadecimal escape");
            }
        }
        String digits = twoHexDigits();
        return java.lang.Integer.parseInt(digits, 16);
    }

    private String twoHexDigits() throws SyntaxError {
        StringBuilder digits = new StringBuilder();
        for (int count = 0; count < 2; count++) {
            if (atEnd() || Character.digit(peek(), 16) < 0) {
                throw error("bad hexadecimal escape");
            }
            digits.append(next());
        }
        return digits.toString();
    }

    private Term number() throws SyntaxError {
        boolean negative = false;
        if (peek() == '-') {
            negative = true;
            next();
        } else if (peek() == '+') {
            next();
        }
        int start = position;
        while (!atEnd() && Character.isDigit(peek())) {
            next();
        }
        String digits = text.substring(start, position);
        if (!atEnd() && peek() == '#') {
            next();
            int base;
            try {
                base = java.lang.Integer.parseInt(digits);
            } catch (NumberFormatException e) {
                throw error("bad base");
            }
            StringBuilder value = new StringBuilder();
            while (!atEnd() && Character.digit(peek(), base) >= 0) {
                value.append(next());
            }
            if (value.length() == 0) {
                throw error("missing digits");
            }
            BigInteger parsed = new BigInteger(value.toString(), base);
            return new Integer(negative ? parsed.negate() : parsed);
        }
        if (!atEnd() && peek() == '.' && position + 1 < text.length()
            && Character.isDigit(text.charAt(position + 1))) {
            next();
            while (!atEnd() && Character.isDigit(peek())) {
                next();
            }
            return parseFloat(negative, start);
        }
        if (!atEnd() && (peek() == 'e' || peek() == 'E')) {
            return parseFloat(negative, start);
        }
        if (digits.isEmpty()) {
            throw error("missing number");
        }
        BigInteger parsed = new BigInteger(digits);
        return new Integer(negative ? parsed.negate() : parsed);
    }

    private Float parseFloat(boolean negative, int start) throws SyntaxError {
        if (!atEnd() && (peek() == 'e' || peek() == 'E')) {
            next();
            if (!atEnd() && (peek() == '-' || peek() == '+')) {
                next();
            }
            while (!atEnd() && Character.isDigit(peek())) {
                next();
            }
        }
        String value = text.substring(start, position);
        try {
            return Float.of((negative ? "-" : "") + value);
        } catch (NumberFormatException e) {
            throw error("bad float");
        }
    }

    private void skipBlanks() {
        while (!atEnd()) {
            char current = peek();
            if (Character.isWhitespace(current)) {
                next();
            } else if (current == '%') {
                while (!atEnd() && peek() != '\n') {
                    next();
                }
            } else {
                break;
            }
        }
    }

    private void expect(char expected) throws SyntaxError {
        if (atEnd() || peek() != expected) {
            throw error("expected '" + expected + "'");
        }
        next();
    }

    private boolean atEnd() {
        return position >= text.length();
    }

    private char peek() {
        // Callers compare against the token that should follow, so the end of
        // the text reads as a character no term can contain rather than an
        // index error that would escape as a Java exception.
        return position < text.length() ? text.charAt(position) : '\0';
    }

    private char next() {
        char current = text.charAt(position++);
        if (current == '\n') {
            line++;
        }
        return current;
    }

    private SyntaxError error(String message) {
        return new SyntaxError(line, message);
    }

}
