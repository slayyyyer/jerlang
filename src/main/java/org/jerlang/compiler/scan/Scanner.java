package org.jerlang.compiler.scan;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.jerlang.type.Atom;
import org.jerlang.type.Float;
import org.jerlang.type.Integer;
import org.jerlang.type.Term;

/**
 * = The Erlang scanner
 *
 * Tokens are erl_scan terms: {atom, Line, foo}, {var, Line, 'X'},
 * {integer, Line, 1}, {string, Line, "text"}, {float, Line, 1.5},
 * {char, Line, 97}, {dot, Line}, and {Symbol, Line} for keywords, operators
 * and punctuation. String values are lists of code points, which is the
 * representation erl_scan returns and the one the term comparison expects.
 *
 * The scanner is deliberately a scanner and not a parser: it does not fold
 * adjacent string tokens, it does not know a record field from a form
 * terminator beyond the dot rule below, and it does not expand macros. Those
 * are the preprocessor's and the parser's jobs, and keeping them out means a
 * scan error always names a character.
 *
 * A dot is the end of a form only when it is followed by whitespace, a
 * comment or the end of the source. That is the rule that lets R#record.field
 * scan as a field access and makes the final dot of a module a dot token.
 *
 * Sigils (OTP 27) are not implemented yet; a tilde outside a string is
 * reported rather than guessed at.
 */
public final class Scanner {

    private static final Set<String> KEYWORDS = new HashSet<>(Arrays.asList(
        "after", "andalso", "band", "bnot", "bor", "bsl", "bsr", "bxor",
        "case", "catch", "cond", "div", "else", "end", "fun", "if", "let",
        "maybe", "not", "of", "or", "orelse", "receive", "rem", "try",
        "when", "xor"));

    // Longest first inside each length: =:= must win over =, ?= over ?, and
    // .. over the field access dot.
    private static final String[] OPERATORS = {
        "=:=", "=/=", "...",
        "->", "=>", ":=", "::", "||", "<-", "=<", "<=", ">=", "==", "/=",
        "++", "--", "<<", ">>", "?=", "..",
        "+", "-", "*", "/", "=", "<", ">", "!", ",", ";", ":", ".", "?",
        "#", "(", ")", "[", "]", "{", "}", "|"
    };

    private static final Atom ATOM = Atom.of("atom");
    private static final Atom VAR = Atom.of("var");
    private static final Atom INTEGER = Atom.of("integer");
    private static final Atom FLOAT = Atom.of("float");
    private static final Atom CHAR = Atom.of("char");
    private static final Atom STRING = Atom.of("string");
    private static final Atom DOT = Atom.of("dot");

    private final String source;
    private final List<Token> tokens;
    private int index;
    private int line;

    private Scanner(String source) {
        this.source = source;
        this.tokens = new ArrayList<>();
        this.index = 0;
        this.line = 1;
    }

    public static List<Token> scan(String source) {
        return new Scanner(source).run();
    }

    private List<Token> run() {
        while (index < source.length()) {
            char character = source.charAt(index);
            if (character == '\n') {
                line++;
                index++;
                continue;
            }
            if (isWhitespace(character)) {
                index++;
                continue;
            }
            if (index == 0 && character == '\uFEFF') {
                // A UTF-8 byte order mark is not a token and not an error;
                // editors add it and erl_scan accepts the source without it.
                index++;
                continue;
            }
            if (character == '%') {
                comment();
                continue;
            }
            if (isDigit(character)) {
                number();
                continue;
            }
            if (character == '"') {
                string();
                continue;
            }
            if (character == '\'') {
                quotedAtom();
                continue;
            }
            if (character == '$') {
                characterLiteral();
                continue;
            }
            if (character >= 'a' && character <= 'z') {
                name(false);
                continue;
            }
            if ((character >= 'A' && character <= 'Z') || character == '_') {
                name(true);
                continue;
            }
            if (!operator()) {
                throw error("unexpected character " + printable(character));
            }
        }
        return tokens;
    }

    private void comment() {
        // The newline stays for the loop, so a comment never changes the line
        // number by itself.
        index++;
        while (index < source.length() && source.charAt(index) != '\n') {
            index++;
        }
    }

    private void number() {
        int start = index;
        int startLine = line;
        skipDigits();
        String digits = source.substring(start, index).replace("_", "");

        if (peek() == '#') {
            int base;
            try {
                base = java.lang.Integer.parseInt(digits);
            } catch (NumberFormatException e) {
                throw error("illegal base " + digits);
            }
            if (base < 2 || base > 36) {
                throw error("illegal base " + base);
            }
            index++;
            int digitsStart = index;
            while (index < source.length()) {
                char character = source.charAt(index);
                if (character == '_' || digitValue(character) >= 0
                    && digitValue(character) < base) {
                    index++;
                } else {
                    break;
                }
            }
            String baseDigits = source.substring(digitsStart, index).replace("_", "");
            if (baseDigits.isEmpty()) {
                throw error("base " + base + " needs digits");
            }
            add(INTEGER, startLine, new Integer(new BigInteger(baseDigits, base)));
            return;
        }

        boolean fraction = false;
        if (peek() == '.' && isDigit(peek(1))) {
            fraction = true;
            index++;
            skipDigits();
        }
        // An exponent is only a float when there is a fraction: 1e3 is an
        // illegal integer in Erlang, not a float.
        if (fraction && (peek() == 'e' || peek() == 'E') && exponentFollows()) {
            index++;
            if (peek() == '+' || peek() == '-') {
                index++;
            }
            skipDigits();
        }

        String text = source.substring(start, index).replace("_", "");
        if (fraction) {
            add(FLOAT, startLine, Float.of(Double.parseDouble(text)));
        } else {
            add(INTEGER, startLine, new Integer(new BigInteger(text)));
        }
    }

    private boolean exponentFollows() {
        char sign = peek(1);
        if (sign == '+' || sign == '-') {
            return isDigit(peek(2));
        }
        return isDigit(sign);
    }

    private void string() {
        int startLine = line;
        index++;
        List<java.lang.Integer> codePoints = new ArrayList<>();
        while (true) {
            if (index >= source.length()) {
                throw error("unterminated string");
            }
            char character = source.charAt(index);
            if (character == '"') {
                index++;
                break;
            }
            if (character == '\\') {
                index++;
                int codePoint = escape();
                if (codePoint >= 0) {
                    codePoints.add(codePoint);
                }
            } else {
                int codePoint = source.codePointAt(index);
                index += Character.charCount(codePoint);
                if (codePoint == '\n') {
                    line++;
                }
                codePoints.add(codePoint);
            }
        }
        add(STRING, startLine, codePointList(codePoints));
    }

    private void quotedAtom() {
        int startLine = line;
        index++;
        StringBuilder text = new StringBuilder();
        while (true) {
            if (index >= source.length()) {
                throw error("unterminated quoted atom");
            }
            char character = source.charAt(index);
            if (character == '\'') {
                index++;
                break;
            }
            if (character == '\\') {
                index++;
                int codePoint = escape();
                if (codePoint >= 0) {
                    text.appendCodePoint(codePoint);
                }
            } else {
                int codePoint = source.codePointAt(index);
                index += Character.charCount(codePoint);
                if (codePoint == '\n') {
                    line++;
                }
                text.appendCodePoint(codePoint);
            }
        }
        add(ATOM, startLine, Atom.of(text.toString()));
    }

    private void characterLiteral() {
        int startLine = line;
        index++;
        if (index >= source.length()) {
            throw error("a character literal needs a character");
        }
        int codePoint;
        if (source.charAt(index) == '\\') {
            index++;
            codePoint = escape();
            if (codePoint < 0) {
                throw error("a character literal cannot be a line continuation");
            }
        } else {
            codePoint = source.codePointAt(index);
            index += Character.charCount(codePoint);
            if (codePoint == '\n') {
                line++;
            }
        }
        add(CHAR, startLine, Integer.of(codePoint));
    }

    /**
     * One escape, after the backslash. Returns the code point, or -1 for a
     * backslash newline, which contributes nothing to a string or an atom.
     */
    private int escape() {
        if (index >= source.length()) {
            throw error("backslash at end of source");
        }
        char character = source.charAt(index++);
        switch (character) {
        case 'b':
            return 8;
        case 'd':
            return 127;
        case 'e':
            return 27;
        case 'f':
            return 12;
        case 'n':
            return 10;
        case 'r':
            return 13;
        case 's':
            return 32;
        case 't':
            return 9;
        case 'v':
            return 11;
        case '\\':
            return '\\';
        case '"':
            return '"';
        case '\'':
            return '\'';
        case '^':
            if (index >= source.length()) {
                throw error("incomplete control escape");
            }
            char control = source.charAt(index++);
            return control == '?' ? 127 : (Character.toUpperCase(control) & 0x1F);
        case 'x':
            return hexEscape();
        case '\n':
            line++;
            return -1;
        case '\r':
            if (peek() == '\n') {
                index++;
            }
            line++;
            return -1;
        default:
            if (character >= '0' && character <= '7') {
                int value = character - '0';
                for (int digits = 1; digits < 3 && peek() >= '0' && peek() <= '7'; digits++) {
                    value = value * 8 + (source.charAt(index++) - '0');
                }
                return value;
            }
            return character;
        }
    }

    private int hexEscape() {
        if (peek() == '{') {
            index++;
            int start = index;
            while (index < source.length() && isHex(source.charAt(index))) {
                index++;
            }
            if (start == index || peek() != '}') {
                throw error("\\x{...} needs hex digits and a closing brace");
            }
            int codePoint = java.lang.Integer.parseInt(source.substring(start, index), 16);
            index++;
            return codePoint;
        }
        if (!isHex(peek()) || !isHex(peek(1))) {
            throw error("\\x needs two hex digits");
        }
        int value = digitValue(source.charAt(index)) * 16 + digitValue(source.charAt(index + 1));
        index += 2;
        return value;
    }

    private void name(boolean variable) {
        int start = index;
        int startLine = line;
        index++;
        while (index < source.length() && isNameCharacter(source.charAt(index))) {
            index++;
        }
        String name = source.substring(start, index);
        if (!variable && KEYWORDS.contains(name)) {
            add(Atom.of(name), startLine);
        } else {
            add(variable ? VAR : ATOM, startLine, Atom.of(name));
        }
    }

    private boolean operator() {
        for (String operator : OPERATORS) {
            if (!source.startsWith(operator, index)) {
                continue;
            }
            int startLine = line;
            index += operator.length();
            if (operator.equals(".") && dotEndsForm(peek())) {
                add(DOT, startLine);
            } else {
                add(Atom.of(operator), startLine);
            }
            return true;
        }
        return false;
    }

    private boolean dotEndsForm(char next) {
        return next == 0 || next == 10 || isWhitespace(next) || next == '%';
    }

    private void skipDigits() {
        while (index < source.length()) {
            char character = source.charAt(index);
            if (isDigit(character) || character == '_') {
                index++;
            } else {
                break;
            }
        }
    }

    private void add(Atom category, int startLine) {
        tokens.add(new Token(category, startLine));
    }

    private void add(Atom category, int startLine, Term value) {
        tokens.add(new Token(category, startLine, value));
    }

    private ScanException error(String message) {
        int lineStart = source.lastIndexOf('\n', index - 1);
        int column = index - lineStart;
        return new ScanException(line, column, message);
    }

    private char peek() {
        return peek(0);
    }

    private char peek(int offset) {
        int at = index + offset;
        return at < source.length() ? source.charAt(at) : 0;
    }

    private static boolean isWhitespace(char character) {
        return character == ' ' || character == '\t' || character == '\r'
            || character == '\f' || character == 0x0B;
    }

    private static boolean isDigit(char character) {
        return character >= '0' && character <= '9';
    }

    private static boolean isHex(char character) {
        return digitValue(character) >= 0;
    }

    private static boolean isNameCharacter(char character) {
        return character >= 'a' && character <= 'z'
            || character >= 'A' && character <= 'Z'
            || isDigit(character) || character == '_' || character == '@';
    }

    private static int digitValue(char character) {
        if (character >= '0' && character <= '9') {
            return character - '0';
        }
        if (character >= 'a' && character <= 'f') {
            return character - 'a' + 10;
        }
        if (character >= 'A' && character <= 'F') {
            return character - 'A' + 10;
        }
        return -1;
    }

    private static String printable(char character) {
        if (character >= 32 && character <= 126) {
            return "'" + character + "'";
        }
        return String.format("U+%04X", (int) character);
    }

    private static Term codePointList(List<java.lang.Integer> codePoints) {
        org.jerlang.type.List list = org.jerlang.type.List.nil;
        for (int position = codePoints.size() - 1; position >= 0; position--) {
            list = new org.jerlang.type.List(Integer.of(codePoints.get(position)), list);
        }
        return list;
    }

}
