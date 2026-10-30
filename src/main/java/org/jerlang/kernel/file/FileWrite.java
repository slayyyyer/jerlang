package org.jerlang.kernel.file;

import java.io.IOException;

import org.jerlang.erts.erlang.Error;
import org.jerlang.type.List;
import org.jerlang.type.Term;

public class FileWrite {

    public static Term dispatch(List params) {
        switch (params.length()) {
        case 2:
            return write_2(params.head(), params.tail().head());
        default:
            throw Error.badarg;
        }
    }

    /**
     * Write iodata to the file the io device names.
     *
     * http://www.erlang.org/doc/man/file.html#write-2
     */
    public static Term write_2(Term descriptor, Term data) {
        FileDescriptors.Handle handle = FileDescriptors.handle(descriptor);
        if (!handle.writable()) {
            return FileSupport.error(FileSupport.badFileDescriptor());
        }
        byte[] bytes = FileSupport.data(data);
        try {
            handle.write(bytes);
            return FileSupport.OK;
        } catch (IOException e) {
            return FileSupport.error(FileSupport.posix(e));
        }
    }

}
