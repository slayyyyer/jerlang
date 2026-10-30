package org.jerlang.kernel.file;

import java.io.IOException;

import org.jerlang.erts.erlang.Error;
import org.jerlang.type.Integer;
import org.jerlang.type.List;
import org.jerlang.type.Term;
import org.jerlang.type.Tuple;

public class FileRead {

    public static Term dispatch(List params) {
        switch (params.length()) {
        case 2:
            return read_2(params.head(), params.tail().head());
        default:
            throw Error.badarg;
        }
    }

    /**
     * Read Count bytes. Fewer bytes are returned only at end of file, where
     * the atom eof means that nothing was left to read.
     *
     * http://www.erlang.org/doc/man/file.html#read-2
     */
    public static Term read_2(Term descriptor, Term count) {
        if (!(count instanceof Integer)) {
            throw Error.badarg;
        }
        int bytes = count.toInteger().toInt();
        if (bytes < 0) {
            throw Error.badarg;
        }
        FileDescriptors.Handle handle = FileDescriptors.handle(descriptor);
        if (!handle.readable()) {
            return FileSupport.error(FileSupport.badFileDescriptor());
        }
        byte[] data;
        try {
            data = handle.read(bytes);
        } catch (IOException e) {
            return FileSupport.error(FileSupport.posix(e));
        }
        if (data == null) {
            return FileSupport.EOF;
        }
        return Tuple.of(FileSupport.OK, FileSupport.data(data, handle.binary()));
    }

}
