package org.jerlang.stdlib;

import org.jerlang.stdlib.unicode.UnicodeCharactersToBinary;
import org.jerlang.stdlib.unicode.UnicodeCharactersToList;
import org.jerlang.type.Term;

/**
 * = unicode
 *
 * Character encoding conversion.
 *
 * latin1, unicode and utf8 are the encodings accepted here. A character list
 * may mix codepoints and binaries, and a binary is always decoded with the
 * input encoding.
 *
 * http://www.erlang.org/doc/man/unicode.html
 */
public class Unicode {

    /**
     * Convert characters to UTF-8. The input is unicode.
     */
    public static Term characters_to_binary(Term data) {
        return UnicodeCharactersToBinary.characters_to_binary_1(data);
    }

    /**
     * Convert characters to UTF-8. The input data is decoded with
     * InEncoding.
     */
    public static Term characters_to_binary(Term data, Term inEncoding) {
        return UnicodeCharactersToBinary.characters_to_binary_2(data, inEncoding);
    }

    /**
     * Convert characters from InEncoding to OutEncoding.
     */
    public static Term characters_to_binary(Term data, Term inEncoding, Term outEncoding) {
        return UnicodeCharactersToBinary.characters_to_binary_3(data, inEncoding, outEncoding);
    }

    /**
     * Convert characters to a list of codepoints. The input is unicode.
     */
    public static Term characters_to_list(Term data) {
        return UnicodeCharactersToList.characters_to_list_1(data);
    }

    /**
     * Convert characters to a list of codepoints, decoding binaries with
     * InEncoding.
     */
    public static Term characters_to_list(Term data, Term inEncoding) {
        return UnicodeCharactersToList.characters_to_list_2(data, inEncoding);
    }

}
