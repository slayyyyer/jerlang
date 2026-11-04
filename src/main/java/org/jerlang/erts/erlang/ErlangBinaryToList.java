package org.jerlang.erts.erlang;

import org.jerlang.type.Binary;
import org.jerlang.type.List;
import org.jerlang.type.Term;

/**
 * = erlang:binary_to_list/1
 *
 * A {@link Binary} is a byte-aligned {@link org.jerlang.type.BitString}, so the
 * existing conversion already returns the byte values in order. Keeping this
 * entry point separate preserves binary_to_list's restriction to binaries.
 */
public final class ErlangBinaryToList {

    private ErlangBinaryToList() {
    }

    public static Term dispatch(List params) {
        switch (params.length()) {
        case 1:
            return binary_to_list_1(params.head().toBinary());
        default:
            throw Error.badarg;
        }
    }

    public static List binary_to_list_1(Binary binary) {
        return binary.convert_to_list();
    }

}
