package org.jerlang.kernel.file;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import org.jerlang.erts.erlang.Error;
import org.jerlang.type.Atom;
import org.jerlang.type.List;
import org.jerlang.type.Term;
import org.jerlang.type.Tuple;

public class FileOpen {

    private static final Atom APPEND = Atom.of("append");
    private static final Atom BINARY = Atom.of("binary");
    private static final Atom COMPRESSED = Atom.of("compressed");
    private static final Atom EXCLUSIVE = Atom.of("exclusive");
    private static final Atom LIST = Atom.of("list");
    private static final Atom RAW = Atom.of("raw");
    private static final Atom READ = Atom.of("read");
    private static final Atom SYNC = Atom.of("sync");
    private static final Atom WRITE = Atom.of("write");

    public static Term dispatch(List params) {
        switch (params.length()) {
        case 1:
            return open_1(params.head());
        case 2:
            return open_2(params.head(), params.tail().head());
        default:
            throw Error.badarg;
        }
    }

    /**
     * http://www.erlang.org/doc/man/file.html#open-1
     */
    public static Term open_1(Term filename) {
        return open(filename, List.of(READ));
    }

    /**
     * Open a file and return an io device that file:read/2, file:write/2 and
     * file:close/1 accept.
     *
     * The mode list is read like OTP's: read alone opens for reading, write
     * alone replaces the file, read and write update it in place, append
     * starts at the end, and binary decides whether data is returned as
     * binaries or strings. raw, sync and delayed_write do not change what the
     * caller sees, so they are accepted and ignored.
     *
     * http://www.erlang.org/doc/man/file.html#open-2
     */
    public static Term open_2(Term filename, Term modes) {
        return open(filename, modes);
    }

    private static Term open(Term filename, Term modes) {
        if (!(modes instanceof List)) {
            throw Error.badarg;
        }
        boolean read = false;
        boolean write = false;
        boolean append = false;
        boolean exclusive = false;
        boolean binary = false;
        List rest = modes.toList();
        while (rest.length() > 0) {
            Term mode = rest.head();
            if (READ.equals(mode)) {
                read = true;
            } else if (WRITE.equals(mode)) {
                write = true;
            } else if (APPEND.equals(mode)) {
                append = true;
                write = true;
            } else if (EXCLUSIVE.equals(mode)) {
                exclusive = true;
            } else if (BINARY.equals(mode)) {
                binary = true;
            } else if (LIST.equals(mode)) {
                binary = false;
            } else if (RAW.equals(mode) || SYNC.equals(mode)) {
                // The descriptor is a RandomAccessFile: there is no buffer to
                // flush and no file server to pass a raw request to.
            } else if (COMPRESSED.equals(mode)) {
                return FileSupport.error(FileSupport.notSupported());
            } else if (mode instanceof Tuple && Atom.of("delayed_write").equals(((Tuple) mode).element(1))) {
                // A buffering hint, and every write already goes straight out.
            } else if (mode instanceof Tuple && Atom.of("encoding").equals(((Tuple) mode).element(1))) {
                // Bytes are returned as they are stored; a string is UTF-8.
            } else {
                throw Error.badarg;
            }
            rest = rest.tail();
        }
        if (!read && !write) {
            throw Error.badarg;
        }

        Path path = Paths.get(FileSupport.path(filename));
        if (Files.isDirectory(path)) {
            return FileSupport.error(FileSupport.posix(
                new java.nio.file.FileSystemException(path.toString(), null, "Is a directory")));
        }
        if (exclusive && write && Files.exists(path)) {
            return FileSupport.error(FileSupport.posix(
                new java.nio.file.FileAlreadyExistsException(path.toString())));
        }

        RandomAccessFile file;
        try {
            // "rw" creates the file, which is what write and append need.
            // A read-only open of a missing file fails with enoent.
            file = new RandomAccessFile(path.toFile(), read && !write ? "r" : "rw");
        } catch (IOException e) {
            return FileSupport.error(FileSupport.posix(e));
        }
        try {
            // write alone replaces the file. read and write together update
            // the file in place, which is what a caller that opens both
            // expects.
            if (write && !read && !append) {
                file.setLength(0);
            }
            if (append) {
                file.seek(file.length());
            }
        } catch (IOException e) {
            closeQuietly(file);
            return FileSupport.error(FileSupport.posix(e));
        }
        Tuple descriptor = FileDescriptors.register(path.toString(), file, binary,
            read || !write, write, append);
        return Tuple.of(FileSupport.OK, descriptor);
    }

    private static void closeQuietly(RandomAccessFile file) {
        try {
            file.close();
        } catch (IOException ignored) {
            // The open already failed; the close is only cleanup.
        }
    }

}
