package org.jerlang.stdlib.unicode;

import org.jerlang.erts.erlang.Error;
import org.jerlang.type.Atom;
import org.jerlang.type.List;
import org.jerlang.type.Term;

public class UnicodeCharactersToBinary {

    private UnicodeCharactersToBinary() {
    }

    public static Term dispatch(List params) {
        switch (params.length()) {
        case 1:
            return characters_to_binary_1(params.head());
        case 2:
            return characters_to_binary_2(params.head(), params.tail().head());
        case 3:
            return characters_to_binary_3(params.head(), params.tail().head(),
                params.tail().tail().head());
        default:
            throw Error.badarg;
        }
    }

    public static Term characters_to_binary_1(Term data) {
        return UnicodeConversion.charactersToBinary(data, Atom.of("unicode"), Atom.of("utf8"));
    }

    public static Term characters_to_binary_2(Term data, Term inEncoding) {
        return UnicodeConversion.charactersToBinary(data, inEncoding.toAtom(), Atom.of("utf8"));
    }

    public static Term characters_to_binary_3(Term data, Term inEncoding, Term outEncoding) {
        return UnicodeConversion.charactersToBinary(data, inEncoding.toAtom(),
            outEncoding.toAtom());
    }

}
