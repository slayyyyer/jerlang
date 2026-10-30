package org.jerlang.kernel.file;

import java.io.IOException;

import org.jerlang.erts.erlang.Error;
import org.jerlang.type.List;
import org.jerlang.type.Term;
import org.jerlang.type.Tuple;

public class FileReadLine {

    public static Term dispatch(List params) {
        switch (params.length()) {
        case 1:
            return read_line_1(params.head());
        default:
            throw Error.badarg;
        }
    }

    /**
     * Read one line, including the line feed. eof means that nothing was left
     * to read.
     *
     * http://www.erlang.org/doc/man/file.html#read_line-1
     */
    public static Term read_line_1(Term descriptor) {
        FileDescriptors.Handle handle = FileDescriptors.handle(descriptor);
        if (!handle.readable()) {
            return FileSupport.error(FileSupport.badFileDescriptor());
        }
        byte[] line;
        try {
            line = handle.readLine();
        } catch (IOException e) {
            return FileSupport.error(FileSupport.posix(e));
        }
        if (line == null) {
            return FileSupport.EOF;
        }
        return Tuple.of(FileSupport.OK, FileSupport.data(line, handle.binary()));
    }

}
