package org.jerlang.stdlib;

import org.jerlang.stdlib.io_lib.IoLibFormat;
import org.jerlang.stdlib.io_lib.IoLibPrintableLatin1List;
import org.jerlang.stdlib.io_lib.IoLibPrintableList;
import org.jerlang.stdlib.io_lib.IoLibPrintableUnicodeList;
import org.jerlang.type.Term;

/**
 * = io_lib
 *
 * The formatting half of the io module: the functions that build text from a
 * format string and its arguments without writing it anywhere.
 *
 * http://www.erlang.org/doc/man/io_lib.html
 */
public class IOLib {

    /**
     * Format a text. The result is a character list, which is what
     * unicode:characters_to_binary/1 or io:put_chars/1 expects.
     */
    public static Term format(Term format, Term arguments) {
        return IoLibFormat.format_2(format, arguments);
    }

    /**
     * Whether a flat proper list of latin1 characters is printable. Used by
     * the formatters to decide between "text" and a list of integers.
     */
    public static Term printable_list(Term data) {
        return IoLibPrintableList.printable_list_1(data);
    }

    /**
     * The latin1 variant, which is the same set as printable_list/1.
     */
    public static Term printable_latin1_list(Term data) {
        return IoLibPrintableLatin1List.printable_latin1_list_1(data);
    }

    /**
     * Whether a flat proper list of unicode codepoints is printable.
     */
    public static Term printable_unicode_list(Term data) {
        return IoLibPrintableUnicodeList.printable_unicode_list_1(data);
    }

}
