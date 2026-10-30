package org.jerlang.kernel.file;

import java.io.IOException;

import org.jerlang.erts.erlang.Error;
import org.jerlang.type.Atom;
import org.jerlang.type.Integer;
import org.jerlang.type.List;
import org.jerlang.type.Term;
import org.jerlang.type.Tuple;

public class FilePosition {

    private static final Atom BOF = Atom.of("bof");
    private static final Atom CUR = Atom.of("cur");
    private static final Atom EOF = Atom.of("eof");

    public static Term dispatch(List params) {
        switch (params.length()) {
        case 1:
            return position_1(params.head());
        case 2:
            return position_2(params.head(), params.tail().head());
        default:
            throw Error.badarg;
        }
    }

    /**
     * The current position, in bytes from the beginning of the file.
     *
     * http://www.erlang.org/doc/man/file.html#position-1
     */
    public static Term position_1(Term descriptor) {
        FileDescriptors.Handle handle = FileDescriptors.handle(descriptor);
        try {
            return Tuple.of(FileSupport.OK, new Integer(handle.position()));
        } catch (IOException e) {
            return FileSupport.error(FileSupport.posix(e));
        }
    }

    /**
     * Move the position. Location is an offset from the beginning, or bof,
     * cur or eof, or one of those with an offset.
     *
     * http://www.erlang.org/doc/man/file.html#position-2
     */
    public static Term position_2(Term descriptor, Term location) {
        FileDescriptors.Handle handle = FileDescriptors.handle(descriptor);
        long offset;
        try {
            if (location instanceof Integer) {
                offset = location.toInteger().toLong();
            } else if (location instanceof Atom) {
                offset = resolve(handle, (Atom) location, 0);
            } else if (location instanceof Tuple && ((Tuple) location).arity() == 2
                && ((Tuple) location).element(1) instanceof Atom
                && ((Tuple) location).element(2) instanceof Integer) {
                offset = resolve(handle, (Atom) ((Tuple) location).element(1),
                    ((Tuple) location).element(2).toInteger().toLong());
            } else {
                throw Error.badarg;
            }
            if (offset < 0) {
                return FileSupport.error(FileSupport.invalidArgument());
            }
            return Tuple.of(FileSupport.OK, new Integer(handle.seek(offset)));
        } catch (IOException e) {
            return FileSupport.error(FileSupport.posix(e));
        }
    }

    private static long resolve(FileDescriptors.Handle handle, Atom base, long offset)
        throws IOException {
        if (BOF.equals(base)) {
            return offset;
        }
        if (CUR.equals(base)) {
            return handle.position() + offset;
        }
        if (EOF.equals(base)) {
            return handle.length() + offset;
        }
        throw Error.badarg;
    }

}
