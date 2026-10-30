package org.jerlang.stdlib.unicode;

import org.jerlang.erts.erlang.Error;
import org.jerlang.type.Atom;
import org.jerlang.type.List;
import org.jerlang.type.Term;

public class UnicodeCharactersToList {

    private UnicodeCharactersToList() {
    }

    public static Term dispatch(List params) {
        switch (params.length()) {
        case 1:
            return characters_to_list_1(params.head());
        case 2:
            return characters_to_list_2(params.head(), params.tail().head());
        default:
            throw Error.badarg;
        }
    }

    public static Term characters_to_list_1(Term data) {
        return UnicodeConversion.charactersToList(data, Atom.of("unicode"));
    }

    public static Term characters_to_list_2(Term data, Term inEncoding) {
        return UnicodeConversion.charactersToList(data, inEncoding.toAtom());
    }

}
