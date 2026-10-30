package org.jerlang.stdlib.io_lib;

import org.jerlang.type.Integer;
import org.jerlang.type.List;
import org.jerlang.type.Str;
import org.jerlang.type.Term;

/**
 * The predicates behind io_lib:printable_list/1,
 * printable_latin1_list/1 and printable_unicode_list/1.
 *
 * A printable list is flat and proper: every element is a character
 * codepoint, with the escape characters and the printable range OTP accepts.
 * The unicode variant allows codepoints above latin1 but still rejects the
 * C1 control range and the surrogate block.
 */
final class Printable {

    private Printable() {
    }

    static boolean unicode(Term term) {
        List list = asList(term);
        if (list == null) {
            return false;
        }
        while (!empty(list)) {
            Term head = list.head();
            if (!(head instanceof Integer)) {
                return false;
            }
            int code = ((Integer) head).toInt();
            boolean printable = code == '\t' || code == '\n' || code == 11
                || code == '\f' || code == '\r' || code == 27
                || (code >= 32 && code <= 0x10FFFF
                    && !(code >= 0x7F && code <= 0x9F)
                    && !(code >= 0xD800 && code <= 0xDFFF));
            if (!printable) {
                return false;
            }
            list = list.tail();
        }
        return true;
    }

    static boolean latin1(Term term) {
        List list = asList(term);
        if (list == null) {
            return false;
        }
        while (!empty(list)) {
            Term head = list.head();
            if (!(head instanceof Integer)) {
                return false;
            }
            int code = ((Integer) head).toInt();
            boolean printable = code == '\t' || code == '\n' || code == 11
                || code == '\f' || code == '\r' || code == 27
                || (code >= 32 && code <= 126)
                || (code >= 160 && code <= 255);
            if (!printable) {
                return false;
            }
            list = list.tail();
        }
        return true;
    }

    private static List asList(Term term) {
        return term instanceof List ? (List) term : null;
    }

    private static boolean empty(List list) {
        if (list instanceof Str) {
            return ((Str) list).string().isEmpty();
        }
        return list == null || list == List.nil || list.length() == 0;
    }

}
