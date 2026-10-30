package org.jerlang.kernel.file;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;

import org.jerlang.erts.erlang.Error;
import org.jerlang.type.Atom;
import org.jerlang.type.List;
import org.jerlang.type.Term;
import org.jerlang.type.Tuple;

public class FileWriteFile {

    private static final Atom APPEND = Atom.of("append");
    private static final Atom DELAYED_WRITE = Atom.of("delayed_write");
    private static final Atom EXCLUSIVE = Atom.of("exclusive");
    private static final Atom RAW = Atom.of("raw");
    private static final Atom SYNC = Atom.of("sync");

    public static Term dispatch(List params) {
        switch (params.length()) {
        case 2:
            return write_file_2(params.head(), params.tail().head());
        case 3:
            return write_file_3(params.head(), params.tail().head(), params.tail().tail().head());
        default:
            throw Error.badarg;
        }
    }

    /**
     * Write the whole file.
     *
     * http://www.erlang.org/doc/man/file.html#write_file-2
     */
    public static Term write_file_2(Term filename, Term data) {
        return write(filename, data, false, false);
    }

    /**
     * Write the whole file, honoring the append and exclusive modes.
     *
     * http://www.erlang.org/doc/man/file.html#write_file-3
     */
    public static Term write_file_3(Term filename, Term data, Term modes) {
        if (!(modes instanceof List)) {
            throw Error.badarg;
        }
        boolean append = false;
        boolean exclusive = false;
        List rest = modes.toList();
        while (rest.length() > 0) {
            Term mode = rest.head();
            if (APPEND.equals(mode)) {
                append = true;
            } else if (EXCLUSIVE.equals(mode)) {
                exclusive = true;
            } else if (RAW.equals(mode) || SYNC.equals(mode) || DELAYED_WRITE.equals(mode)) {
                // Nothing to flush or buffer here: every write goes straight
                // to the file.
            } else if (mode instanceof Tuple && DELAYED_WRITE.equals(((Tuple) mode).element(1))) {
                // {delayed_write, Size, Delay} is a buffering hint.
            } else {
                throw Error.badarg;
            }
            rest = rest.tail();
        }
        return write(filename, data, append, exclusive);
    }

    private static Term write(Term filename, Term data, boolean append, boolean exclusive) {
        byte[] bytes = FileSupport.data(data);
        Path path = Paths.get(FileSupport.path(filename));
        if (exclusive && Files.exists(path)) {
            return FileSupport.error(FileSupport.posix(
                new java.nio.file.FileAlreadyExistsException(path.toString())));
        }
        if (Files.isDirectory(path)) {
            return FileSupport.error(FileSupport.posix(
                new java.nio.file.FileSystemException(path.toString(), null, "Is a directory")));
        }
        StandardOpenOption[] options = append
            ? new StandardOpenOption[] { StandardOpenOption.CREATE, StandardOpenOption.APPEND }
            : new StandardOpenOption[] { StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE };
        try {
            Files.write(path, bytes, options);
            return FileSupport.OK;
        } catch (IOException e) {
            return FileSupport.error(FileSupport.posix(e));
        }
    }

}
