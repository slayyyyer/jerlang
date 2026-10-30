package org.jerlang.stdlib.io;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import org.jerlang.erts.erlang.Error;
import org.jerlang.type.Atom;
import org.jerlang.type.Binary;
import org.jerlang.type.BitString;
import org.jerlang.type.Integer;
import org.jerlang.type.Number;
import org.jerlang.type.Str;
import org.jerlang.type.Term;
import org.jerlang.util.TermText;

/**
 * The format language of io:format/2 and io_lib:format/2.
 *
 * A control is ~F.P.PadC, where the field width F and the precision P are
 * numbers or * (take the next argument), Pad is one character and C is the
 * control. A negative field width, or a negative width argument, left
 * justifies. The precision means the number of decimals for ~f, the number of
 * significant digits for ~e and ~g, the base for ~b and the maximum number of
 * characters for ~s.
 *
 * The controls implemented here are the ones the standard library uses:
 * ~s ~ts ~c ~tc ~p ~tp ~w ~tw ~P ~W ~b ~B ~f ~e ~g ~a ~i ~n ~~.
 */
public final class FormatEngine {

    private static final Atom STANDARD_IO = Atom.of("standard_io");
    private static final Atom STANDARD_ERROR = Atom.of("standard_error");
    private static final Atom OK = Atom.of("ok");

    private FormatEngine() {
    }

    /**
     * The text of a format string and its arguments.
     */
    public static String format(Term format, Term arguments) {
        if (!(arguments instanceof org.jerlang.type.List)) {
            throw Error.badarg;
        }
        String formatString = stringOf(format);
        Term[] args = toArray((org.jerlang.type.List) arguments);
        int[] position = { 0 };
        StringBuilder out = new StringBuilder();
        int index = 0;
        while (index < formatString.length()) {
            char c = formatString.charAt(index++);
            if (c != '~') {
                out.append(c);
                continue;
            }
            if (index >= formatString.length()) {
                throw Error.badarg;
            }

            boolean left = false;
            int width = 0;
            boolean widthSet = false;

            char next = formatString.charAt(index);
            if (next == '-') {
                left = true;
                index++;
                next = formatString.charAt(index);
            }
            if (next == '*') {
                index++;
                Term widthArgument = next(args, position);
                width = widthArgument.toInteger().toInt();
                widthSet = true;
                if (width < 0) {
                    left = true;
                    width = -width;
                }
            } else {
                while (index < formatString.length() && Character.isDigit(formatString.charAt(index))) {
                    width = width * 10 + (formatString.charAt(index) - '0');
                    index++;
                    widthSet = true;
                }
            }

            int precision = 0;
            boolean precisionSet = false;
            if (index < formatString.length() && formatString.charAt(index) == '.') {
                index++;
                while (index < formatString.length() && Character.isDigit(formatString.charAt(index))) {
                    precision = precision * 10 + (formatString.charAt(index) - '0');
                    index++;
                    precisionSet = true;
                }
            }

            char pad = ' ';
            if (index < formatString.length() && formatString.charAt(index) == '.') {
                index++;
                if (index >= formatString.length()) {
                    throw Error.badarg;
                }
                pad = formatString.charAt(index++);
            }

            if (index >= formatString.length()) {
                throw Error.badarg;
            }
            char control = formatString.charAt(index++);

            // ~t is a modifier that asks for unicode aware output; the
            // conversion of a binary to text is the only place it shows.
            boolean unicode = false;
            if (control == 't') {
                if (index >= formatString.length()) {
                    throw Error.badarg;
                }
                unicode = true;
                control = formatString.charAt(index++);
            }

            String text;
            switch (Character.toLowerCase(control)) {
            case 'n':
                text = "\n";
                widthSet = false;
                break;
            case '~':
                text = "~";
                widthSet = false;
                break;
            case 'i':
                next(args, position);
                text = "";
                widthSet = false;
                break;
            case 'a':
                text = next(args, position).toAtom().toString();
                break;
            case 'c': {
                int code = next(args, position).toInteger().toInt();
                if (code < 0 || code > 0x10FFFF || (code >= 0xD800 && code <= 0xDFFF)) {
                    throw Error.badarg;
                }
                text = new String(Character.toChars(code));
                break;
            }
            case 's': {
                String string = stringOf(next(args, position), unicode);
                if (precisionSet && string.length() > precision) {
                    string = string.substring(0, precision);
                }
                text = string;
                break;
            }
            case 'p':
            case 'w':
                text = TermText.print(next(args, position),
                    Character.toLowerCase(control) == 'p');
                break;
            case 'P':
            case 'W': {
                Term term = next(args, position);
                int depth = next(args, position).toInteger().toInt();
                text = TermText.print(term, Character.toLowerCase(control) == 'p', depth);
                break;
            }
            case 'b': {
                Term term = next(args, position);
                int base = precisionSet ? precision : 10;
                if (base < 2 || base > 36) {
                    throw Error.badarg;
                }
                BigInteger value = term.toInteger().toBigInteger();
                String digits = value.abs().toString(base);
                if (Character.isUpperCase(control)) {
                    digits = digits.toUpperCase();
                }
                text = (value.signum() < 0 ? "-" : "") + digits;
                break;
            }
            case 'f': {
                Number number = next(args, position).toNumber();
                int decimals = precisionSet ? precision : 6;
                text = number.toBigDecimal()
                    .setScale(decimals, RoundingMode.HALF_EVEN)
                    .toPlainString();
                break;
            }
            case 'e':
            case 'g': {
                Number number = next(args, position).toNumber();
                text = scientific(number.toBigDecimal(), precisionSet ? precision : 6,
                    Character.isUpperCase(control));
                break;
            }
            default:
                throw Error.badarg;
            }

            if (widthSet && width > 0) {
                text = pad(text, width, left, pad);
            }
            out.append(text);
        }
        return out.toString();
    }

    /**
     * Write formatted text to the standard output or the standard error
     * stream. Other io devices need a file server, which this runtime does
     * not have, so they are rejected instead of silently written to stdout.
     */
    public static Term formatTo(Term device, Term format, Term arguments) {
        Atom name = device instanceof Atom ? (Atom) device : null;
        if (name == null || !(name.equals(STANDARD_IO) || name.equals(STANDARD_ERROR))) {
            throw Error.badarg;
        }
        String text = format(format, arguments);
        if (name.equals(STANDARD_ERROR)) {
            System.err.print(text);
        } else {
            System.out.print(text);
        }
        return OK;
    }

    private static String pad(String text, int width, boolean left, char fill) {
        int missing = width - text.length();
        if (missing <= 0) {
            return text;
        }
        StringBuilder sb = new StringBuilder(width);
        if (left) {
            sb.append(text);
            for (int index = 0; index < missing; index++) {
                sb.append(fill);
            }
            return sb.toString();
        }
        if (fill == '0' && text.startsWith("-")) {
            // Keep the sign in front of the padding, as ~10.2.0f does.
            sb.append('-');
            for (int index = 0; index < missing; index++) {
                sb.append(fill);
            }
            return sb.append(text, 1, text.length()).toString();
        }
        for (int index = 0; index < missing; index++) {
            sb.append(fill);
        }
        return sb.append(text).toString();
    }

    /**
     * A float in scientific notation with a fixed number of significant
     * digits, as ~e and ~g print it: exponents are signed and not padded.
     */
    private static String scientific(BigDecimal value, int digits, boolean upper) {
        if (digits < 1) {
            digits = 1;
        }
        double number = value.doubleValue();
        if (number == 0.0) {
            StringBuilder zero = new StringBuilder("0");
            if (digits > 1) {
                zero.append('.');
                for (int index = 1; index < digits; index++) {
                    zero.append('0');
                }
            }
            return zero.append(upper ? "E+0" : "e+0").toString();
        }
        int exponent = (int) Math.floor(Math.log10(Math.abs(number)));
        BigDecimal mantissa = value.movePointLeft(exponent).setScale(digits - 1, RoundingMode.HALF_EVEN);
        if (mantissa.abs().compareTo(BigDecimal.TEN) >= 0) {
            mantissa = mantissa.movePointLeft(1).setScale(digits - 1, RoundingMode.HALF_EVEN);
            exponent++;
        }
        return mantissa.toPlainString() + (upper ? "E" : "e")
            + (exponent < 0 ? "-" : "+") + Math.abs(exponent);
    }

    private static Term next(Term[] args, int[] position) {
        if (position[0] >= args.length) {
            throw Error.badarg;
        }
        return args[position[0]++];
    }

    private static Term[] toArray(org.jerlang.type.List list) {
        List<Term> terms = new ArrayList<>();
        org.jerlang.type.List rest = list;
        while (rest.length() > 0) {
            terms.add(rest.head());
            rest = rest.tail();
        }
        return terms.toArray(new Term[0]);
    }

    private static String stringOf(Term term) {
        return stringOf(term, false);
    }

    private static String stringOf(Term term, boolean unicode) {
        if (term instanceof Str) {
            return ((Str) term).string();
        }
        if (term instanceof Binary || term instanceof BitString) {
            byte[] bytes = ((BitString) term).toByteArray();
            return new String(bytes, unicode ? StandardCharsets.UTF_8 : StandardCharsets.ISO_8859_1);
        }
        if (term instanceof org.jerlang.type.List) {
            return Str.convert((org.jerlang.type.List) term).string();
        }
        throw Error.badarg;
    }

}
