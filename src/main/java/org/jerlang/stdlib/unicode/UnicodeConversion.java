package org.jerlang.stdlib.unicode;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;

import org.jerlang.erts.erlang.Error;
import org.jerlang.type.Atom;
import org.jerlang.type.Binary;
import org.jerlang.type.BitString;
import org.jerlang.type.Integer;
import org.jerlang.type.List;
import org.jerlang.type.Str;
import org.jerlang.type.Term;
import org.jerlang.type.Tuple;

/**
 * The conversions behind the unicode module.
 *
 * A character list is a list whose elements are codepoints or binaries, with
 * nested lists allowed, exactly the data characters_to_binary/1 accepts. A
 * binary is decoded with the input encoding; the output encoding controls how
 * the codepoints are written back.
 *
 * On invalid input the functions return {error, Converted, Rest}, and on a
 * UTF-8 sequence cut short at the end of the input {incomplete, Converted,
 * Rest}. Rest is the part of the input that could not be converted; for a
 * list it keeps the shape OTP gives an invalid character, wrapped in its own
 * list.
 */
public final class UnicodeConversion {

    private static final Atom ERROR = Atom.of("error");
    private static final Atom INCOMPLETE = Atom.of("incomplete");

    private static final int LATIN1 = 1;
    private static final int UTF8 = 2;

    private UnicodeConversion() {
    }

    public static Term charactersToBinary(Term data, Atom inputEncoding, Atom outputEncoding) {
        int in = encoding(inputEncoding);
        int out = encoding(outputEncoding);
        ArrayList<java.lang.Integer> points = new ArrayList<>();
        try {
            decode(data, in, points);
        } catch (DecodeFailure failure) {
            return Tuple.of(ERROR, Binary.of(encodePrefix(points, out)), failure.rest);
        } catch (IncompleteFailure incomplete) {
            return Tuple.of(INCOMPLETE, Binary.of(encodePrefix(points, out)), incomplete.rest);
        }
        try {
            return Binary.of(encode(points, out));
        } catch (EncodeFailure failure) {
            ArrayList<java.lang.Integer> converted = new ArrayList<>(points.subList(0, failure.index));
            return Tuple.of(ERROR, Binary.of(encodePrefix(converted, out)),
                List.of(remaining(points, failure.index)));
        }
    }

    public static Term charactersToList(Term data, Atom inputEncoding) {
        int in = encoding(inputEncoding);
        ArrayList<java.lang.Integer> points = new ArrayList<>();
        try {
            decode(data, in, points);
        } catch (DecodeFailure failure) {
            return Tuple.of(ERROR, list(points), failure.rest);
        } catch (IncompleteFailure incomplete) {
            return Tuple.of(INCOMPLETE, list(points), incomplete.rest);
        }
        return list(points);
    }

    private static int encoding(Atom encoding) {
        String name = encoding.toString();
        if (name.equals("latin1")) {
            return LATIN1;
        }
        if (name.equals("unicode") || name.equals("utf8") || name.equals("utf-8")) {
            return UTF8;
        }
        throw Error.badarg;
    }

    private static void decode(Term data, int encoding, ArrayList<java.lang.Integer> out) {
        if (data instanceof BitString) {
            decodeBytes(((BitString) data).toByteArray(), encoding, out);
            return;
        }
        if (data instanceof List) {
            decodeList((List) data, encoding, out);
            return;
        }
        throw Error.badarg;
    }

    private static void decodeList(List list, int encoding, ArrayList<java.lang.Integer> out) {
        List rest = list;
        while (!isEmpty(rest)) {
            Term head = rest.head();
            List tail = rest.tail();
            if (tail == null) {
                tail = List.nil;
            }
            if (head instanceof Integer) {
                int code = ((Integer) head).toInt();
                if (!validCodepoint(code)) {
                    throw new DecodeFailure(new List(List.of(head), tail));
                }
                out.add(code);
            } else if (head instanceof BitString) {
                try {
                    decodeBytes(((BitString) head).toByteArray(), encoding, out);
                } catch (DecodeFailure failure) {
                    throw new DecodeFailure(new List(failure.rest, tail));
                }
            } else if (head instanceof List) {
                try {
                    decodeList((List) head, encoding, out);
                } catch (DecodeFailure failure) {
                    throw new DecodeFailure(new List(failure.rest, tail));
                }
            } else {
                throw new DecodeFailure(new List(List.of(head), tail));
            }
            rest = tail;
        }
    }

    private static void decodeBytes(byte[] data, int encoding, ArrayList<java.lang.Integer> out) {
        if (encoding == LATIN1) {
            for (byte value : data) {
                out.add(value & 0xFF);
            }
            return;
        }
        int index = 0;
        while (index < data.length) {
            int first = data[index] & 0xFF;
            if (first < 0x80) {
                out.add(first);
                index++;
                continue;
            }
            int extra;
            int code;
            if ((first & 0xE0) == 0xC0) {
                extra = 1;
                code = first & 0x1F;
            } else if ((first & 0xF0) == 0xE0) {
                extra = 2;
                code = first & 0x0F;
            } else if ((first & 0xF8) == 0xF0) {
                extra = 3;
                code = first & 0x07;
            } else {
                throw new DecodeFailure(tail(data, index));
            }
            if (index + extra >= data.length) {
                throw new IncompleteFailure(tail(data, index));
            }
            for (int offset = 1; offset <= extra; offset++) {
                int next = data[index + offset] & 0xFF;
                if ((next & 0xC0) != 0x80) {
                    throw new DecodeFailure(tail(data, index));
                }
                code = (code << 6) | (next & 0x3F);
            }
            if (!validCodepoint(code)) {
                throw new DecodeFailure(tail(data, index));
            }
            out.add(code);
            index += extra + 1;
        }
    }

    private static boolean validCodepoint(int code) {
        return code >= 0 && code <= 0x10FFFF && !(code >= 0xD800 && code <= 0xDFFF);
    }

    private static byte[] tail(byte[] data, int from) {
        byte[] rest = new byte[data.length - from];
        System.arraycopy(data, from, rest, 0, rest.length);
        return rest;
    }

    private static byte[] encode(ArrayList<java.lang.Integer> points, int encoding) {
        if (encoding == UTF8) {
            return encodeUtf8(points);
        }
        byte[] bytes = new byte[points.size()];
        for (int index = 0; index < points.size(); index++) {
            int code = points.get(index);
            if (code > 0xFF) {
                throw new EncodeFailure(index);
            }
            bytes[index] = (byte) code;
        }
        return bytes;
    }

    private static byte[] encodePrefix(ArrayList<java.lang.Integer> points, int encoding) {
        if (encoding == UTF8) {
            return encodeUtf8(points);
        }
        byte[] bytes = new byte[points.size()];
        for (int index = 0; index < points.size(); index++) {
            int code = points.get(index);
            if (code > 0xFF) {
                break;
            }
            bytes[index] = (byte) code;
        }
        return bytes;
    }

    private static byte[] encodeUtf8(java.util.List<java.lang.Integer> points) {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        for (int code : points) {
            if (code < 0x80) {
                bytes.write(code);
            } else if (code < 0x800) {
                bytes.write(0xC0 | (code >> 6));
                bytes.write(0x80 | (code & 0x3F));
            } else if (code < 0x10000) {
                bytes.write(0xE0 | (code >> 12));
                bytes.write(0x80 | ((code >> 6) & 0x3F));
                bytes.write(0x80 | (code & 0x3F));
            } else {
                bytes.write(0xF0 | (code >> 18));
                bytes.write(0x80 | ((code >> 12) & 0x3F));
                bytes.write(0x80 | ((code >> 6) & 0x3F));
                bytes.write(0x80 | (code & 0x3F));
            }
        }
        return bytes.toByteArray();
    }

    private static List list(ArrayList<java.lang.Integer> points) {
        List result = List.nil;
        for (int index = points.size() - 1; index >= 0; index--) {
            result = new List(Integer.of(points.get(index)), result);
        }
        return result;
    }

    private static List remaining(ArrayList<java.lang.Integer> points, int from) {
        List result = List.nil;
        for (int index = points.size() - 1; index >= from; index--) {
            result = new List(Integer.of(points.get(index)), result);
        }
        return result;
    }

    private static boolean isEmpty(List list) {
        if (list instanceof Str) {
            return ((Str) list).string().isEmpty();
        }
        return list == null || list == List.nil || list.length() == 0;
    }

    private static final class DecodeFailure extends RuntimeException {

        private static final long serialVersionUID = 1L;

        private final Term rest;

        private DecodeFailure(byte[] rest) {
            this.rest = Binary.of(rest);
        }

        private DecodeFailure(Term rest) {
            this.rest = rest;
        }

    }

    private static final class IncompleteFailure extends RuntimeException {

        private static final long serialVersionUID = 1L;

        private final Term rest;

        private IncompleteFailure(byte[] rest) {
            this.rest = Binary.of(rest);
        }

    }

    private static final class EncodeFailure extends RuntimeException {

        private static final long serialVersionUID = 1L;

        private final int index;

        private EncodeFailure(int index) {
            this.index = index;
        }

    }

}
