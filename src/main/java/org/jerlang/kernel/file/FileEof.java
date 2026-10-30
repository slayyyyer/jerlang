package org.jerlang.kernel.file;

import java.io.IOException;

import org.jerlang.erts.erlang.Error;
import org.jerlang.type.Boolean;
import org.jerlang.type.List;
import org.jerlang.type.Term;

public class FileEof {

    public static Term dispatch(List params) {
        switch (params.length()) {
        case 1:
            return eof_1(params.head());
        default:
            throw Error.badarg;
        }
    }

    /**
     * True when the position is at the end of the file.
     *
     * http://www.erlang.org/doc/man/file.html#eof-1
     */
    public static Term eof_1(Term descriptor) {
        FileDescriptors.Handle handle = FileDescriptors.handle(descriptor);
        try {
            return Boolean.of(handle.position() >= handle.length());
        } catch (IOException e) {
            return FileSupport.error(FileSupport.posix(e));
        }
    }

}
