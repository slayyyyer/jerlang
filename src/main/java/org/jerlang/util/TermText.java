package org.jerlang.util;

import java.math.BigDecimal;
import java.util.HashSet;
import java.util.Set;

import org.jerlang.type.Atom;
import org.jerlang.type.BitString;
import org.jerlang.type.Float;
import org.jerlang.type.List;
import org.jerlang.type.Map;
import org.jerlang.type.Str;
import org.jerlang.type.Term;
import org.jerlang.type.Tuple;

/**
 * Writes a term in Erlang syntax, the way io_lib:format/2 does for ~p and ~w.
 *
 * The two controls differ in what they do with a list of characters: ~p
 * writes it as a string when every character is printable, ~w always writes
 * the list of integers. Everything else is written the same way.
 *
 * The depth argument is a nesting limit: a container reached at depth 1 is
 * written as {..}, [..] or #{..}. OTP's pretty printer additionally cuts a
 * container's siblings at the limit ({a,b} at depth 2 becomes {a,...}); this
 * printer does not, so ~P output can contain more than OTP's.
 */
public final class TermText {

    /**
     * The number a term is printed with when no depth limit was given. It is
     * not Integer.MAX_VALUE on purpose: see next().
     */
    public static final int UNLIMITED = -1;

    private static final Set<String> RESERVED = new HashSet<>(java.util.Arrays.asList(
        "after", "andalso", "begin", "case", "catch", "cond", "end", "fun",
        "if", "let", "maybe", "of", "orelse", "receive", "rem", "try",
        "when", "and", "or", "xor", "not", "band", "bor", "bxor", "bnot",
        "bdiv", "bsl", "bsr"));

    private TermText() {
    }

    public static String print(Term term) {
        return print(term, false);
    }

    public static String print(Term term, boolean stringify) {
        return print(term, stringify, UNLIMITED);
    }

    /**
     * The Erlang text of a term. A negative depth means no limit.
     */
    public static String print(Term term, boolean stringify, int depth) {
        return text(term, stringify, depth);
    }

    private static String text(Term term, boolean stringify, int depth) {
        if (term instanceof Atom) {
            return atom(((Atom) term).toString());
        }
        if (term instanceof org.jerlang.type.Integer) {
            return term.toString();
        }
        if (term instanceof Float) {
            return floatText((Float) term);
        }
        if (term instanceof BitString) {
            return bitString((BitString) term);
        }
        if (term instanceof Map) {
            return mapText((Map) term, stringify, depth);
        }
        if (term instanceof Tuple) {
            return tupleText((Tuple) term, stringify, depth);
        }
        if (term instanceof List) {
            return listText((List) term, stringify, depth);
        }
        // Pids, ports, references and funs already print in Erlang syntax.
        return term.toString();
    }

    private static int next(int depth) {
        if (depth < 0) {
            return depth;
        }
        if (depth == 0) {
            return 0;
        }
        return depth - 1;
    }

    private static String tupleText(Tuple tuple, boolean stringify, int depth) {
        if (depth == 1 && tuple.arity() > 0) {
            return "{...}";
        }
        StringBuilder sb = new StringBuilder("{");
        for (int index = 1; index <= tuple.arity(); index++) {
            if (index > 1) {
                sb.append(',');
            }
            sb.append(text(tuple.element(index), stringify, next(depth)));
        }
        return sb.append('}').toString();
    }

    private static String mapText(Map map, boolean stringify, int depth) {
        if (depth == 1 && map.size().toInt() > 0) {
            return "#{...}";
        }
        StringBuilder sb = new StringBuilder("#{");
        List keys = map.keys();
        boolean first = true;
        while (!isEmpty(keys)) {
            Term key = keys.head();
            if (!first) {
                sb.append(',');
            }
            sb.append(text(key, stringify, next(depth)));
            sb.append(" => ");
            sb.append(text(map.get(key), stringify, next(depth)));
            first = false;
            keys = keys.tail();
        }
        return sb.append('}').toString();
    }

    private static String listText(List list, boolean stringify, int depth) {
        if (isEmpty(list)) {
            return "[]";
        }
        if (depth == 1) {
            return "[...]";
        }
        if (stringify && printable(list)) {
            return string(chars(list), '"');
        }
        StringBuilder sb = new StringBuilder("[");
        boolean first = true;
        List rest = list;
        while (!isEmpty(rest)) {
            if (!first) {
                sb.append(',');
            }
            sb.append(text(rest.head(), stringify, next(depth)));
            first = false;
            rest = rest.tail() instanceof List ? rest.tail() : List.nil;
        }
        return sb.append(']').toString();
    }

    private static boolean isEmpty(List list) {
        if (list instanceof Str) {
            return ((Str) list).string().isEmpty();
        }
        return list == null || list == List.nil || list.length() == 0;
    }

    /**
     * The characters of a list. The caller has already checked that every
     * element is an integer.
     */
    private static java.util.List<java.lang.Integer> chars(List list) {
        java.util.List<java.lang.Integer> result = new java.util.ArrayList<>();
        List rest = list;
        while (!isEmpty(rest)) {
            result.add(rest.head().toInteger().toInt());
            rest = rest.tail();
        }
        return result;
    }

    /**
     * Whether a list can be written as a string: every element is a character
     * OTP accepts in one. That is the latin1 printable set, which includes the
     * escape characters.
     */
    private static boolean printable(List list) {
        List rest = list;
        while (!isEmpty(rest)) {
            Term head = rest.head();
            if (!(head instanceof org.jerlang.type.Integer)) {
                return false;
            }
            int code = ((org.jerlang.type.Integer) head).toInt();
            boolean okay = code == '\t' || code == '\n' || code == 11
                || code == '\f' || code == '\r' || code == 27
                || (code >= 32 && code <= 126)
                || (code >= 160 && code <= 255);
            if (!okay) {
                return false;
            }
            rest = rest.tail();
        }
        return true;
    }

    private static String string(java.util.List<java.lang.Integer> characters, char quote) {
        StringBuilder sb = new StringBuilder();
        sb.append(quote);
        for (int code : characters) {
            escape(sb, code, quote);
        }
        return sb.append(quote).toString();
    }

    private static void escape(StringBuilder sb, int code, char quote) {
        switch (code) {
        case '\n':
            sb.append("\\n");
            return;
        case '\r':
            sb.append("\\r");
            return;
        case '\t':
            sb.append("\\t");
            return;
        case 11:
            sb.append("\\v");
            return;
        case '\b':
            sb.append("\\b");
            return;
        case '\f':
            sb.append("\\f");
            return;
        case 27:
            sb.append("\\e");
            return;
        default:
            break;
        }
        if (code == quote || code == '\\') {
            sb.append('\\').append((char) code);
        } else {
            sb.append((char) code);
        }
    }

    /**
     * The name of an atom, quoted when it can not be written bare.
     */
    private static String atom(String name) {
        boolean bare = !name.isEmpty() && name.charAt(0) >= 'a' && name.charAt(0) <= 'z'
            && !RESERVED.contains(name);
        for (int index = 0; bare && index < name.length(); index++) {
            char c = name.charAt(index);
            bare = (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z')
                || (c >= '0' && c <= '9') || c == '_' || c == '@';
        }
        if (bare) {
            return name;
        }
        StringBuilder sb = new StringBuilder("'");
        for (int index = 0; index < name.length(); index++) {
            int code = name.charAt(index);
            if (code == '\'') {
                sb.append("\\'");
            } else if (code == '\\') {
                sb.append("\\\\");
            } else {
                escape(sb, code, '\'');
            }
        }
        return sb.append('\'').toString();
    }

    /**
     * A float in the notation OTP prints: a lower case e and no padding or
     * plus sign in the exponent.
     */
    private static String floatText(Float value) {
        BigDecimal number = value.toBigDecimal();
        String text = number.toString();
        int exponent = Math.max(text.indexOf('E'), text.indexOf('e'));
        if (exponent < 0) {
            return text;
        }
        String mantissa = text.substring(0, exponent);
        String power = text.substring(exponent + 1);
        boolean negative = power.startsWith("-");
        if (negative || power.startsWith("+")) {
            power = power.substring(1);
        }
        while (power.length() > 1 && power.charAt(0) == '0') {
            power = power.substring(1);
        }
        return mantissa + "e" + (negative ? "-" : "") + power;
    }

    private static String bitString(BitString bitString) {
        int[] bytes = bitString.bytes();
        int unused = bitString.unusedBits();
        StringBuilder sb = new StringBuilder("<<");
        boolean first = true;
        int index = 0;
        while (index < bytes.length) {
            int end = index;
            while (end < bytes.length && printableByte(bytes[end])
                && !(unused > 0 && end == bytes.length - 1)) {
                end++;
            }
            if (end > index) {
                if (!first) {
                    sb.append(',');
                }
                sb.append('"');
                for (int position = index; position < end; position++) {
                    escape(sb, bytes[position], '"');
                }
                sb.append('"');
                first = false;
                index = end;
            } else {
                if (!first) {
                    sb.append(',');
                }
                if (unused > 0 && index == bytes.length - 1) {
                    sb.append((bytes[index] >> unused) & 0xFF);
                    sb.append(':').append(8 - unused);
                } else {
                    sb.append(bytes[index]);
                }
                first = false;
                index++;
            }
        }
        return sb.append(">>").toString();
    }

    private static boolean printableByte(int value) {
        return value == '\t' || value == '\n' || value == 11
            || value == '\f' || value == '\r' || value == 27
            || (value >= 32 && value <= 126);
    }

}
