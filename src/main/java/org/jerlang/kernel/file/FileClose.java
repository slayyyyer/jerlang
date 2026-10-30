package org.jerlang.kernel.file;

import org.jerlang.erts.erlang.Error;
import org.jerlang.type.List;
import org.jerlang.type.Term;

public class FileClose {

    public static Term dispatch(List params) {
        switch (params.length()) {
        case 1:
            return close_1(params.head());
        default:
            throw Error.badarg;
        }
    }

    /**
     * http://www.erlang.org/doc/man/file.html#close-1
     */
    public static Term close_1(Term descriptor) {
        return FileDescriptors.close(descriptor);
    }

}
