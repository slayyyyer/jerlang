package org.jerlang.kernel.file;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.nio.file.AccessDeniedException;
import java.nio.file.DirectoryNotEmptyException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.FileSystemException;
import java.nio.file.NoSuchFileException;
import java.nio.file.NotDirectoryException;

import org.jerlang.erts.erlang.Error;
import org.jerlang.type.Atom;
import org.jerlang.type.Binary;
import org.jerlang.type.BitString;
import org.jerlang.type.Integer;
import org.jerlang.type.List;
import org.jerlang.type.Str;
import org.jerlang.type.Term;
import org.jerlang.type.Tuple;

/**
 * Conversions shared by the file functions: file names are strings, and the
 * data written to and read from a file is iodata.
 *
 * A path is the same list of characters Erlang passes around, while a binary
 * is taken as UTF-8. Data accepts everything iodata does: binaries, strings,
 * byte lists and nested lists of those.
 */
public final class FileSupport {

    public static final Atom EOF = Atom.of("eof");
    public static final Atom ERROR = Atom.of("error");
    public static final Atom OK = Atom.of("ok");

    private static final Atom EACCES = Atom.of("eacces");
    private static final Atom EBADF = Atom.of("ebadf");
    private static final Atom EEXIST = Atom.of("eexist");
    private static final Atom EINVAL = Atom.of("einval");
    private static final Atom EIO = Atom.of("eio");
    private static final Atom EISDIR = Atom.of("eisdir");
    private static final Atom ENOTDIR = Atom.of("enotdir");
    private static final Atom ENOTEMPTY = Atom.of("enotempty");
    private static final Atom ENOENT = Atom.of("enoent");
    private static final Atom ENOSPC = Atom.of("enospc");
    private static final Atom ENOTSUP = Atom.of("enotsup");
    private static final Atom EROFS = Atom.of("erofs");

    private FileSupport() {
    }

    /**
     * The name of a file as the operating system expects it.
     */
    public static String path(Term term) {
        if (term instanceof Str) {
            return ((Str) term).string();
        }
        if (term instanceof Binary) {
            return new String(((Binary) term).toByteArray(), java.nio.charset.StandardCharsets.UTF_8);
        }
        if (term instanceof BitString) {
            return new String(((BitString) term).toByteArray(), java.nio.charset.StandardCharsets.UTF_8);
        }
        if (term instanceof List) {
            return Str.convert((List) term).string();
        }
        throw Error.badarg;
    }

    /**
     * Convert Erlang iodata (binaries, strings and nested lists of bytes) to
     * a byte array.
     */
    public static byte[] data(Term term) {
        if (term instanceof Binary) {
            return ((Binary) term).toByteArray();
        }
        if (term instanceof BitString) {
            return ((BitString) term).toByteArray();
        }
        if (term instanceof Integer) {
            int value = term.toInteger().toInt();
            if (value < 0 || value > 255) {
                throw Error.badarg;
            }
            return new byte[] { (byte) value };
        }
        if (term instanceof Str) {
            return ((Str) term).string().getBytes(java.nio.charset.StandardCharsets.UTF_8);
        }
        if (term instanceof List) {
            java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream();
            List list = (List) term;
            while (list.length() > 0) {
                byte[] element = data(list.head());
                bytes.write(element, 0, element.length);
                list = list.tail();
            }
            return bytes.toByteArray();
        }
        throw Error.badarg;
    }

    /**
     * The bytes of a read, as the io protocol returns them: a binary in binary
     * mode and a string in list mode.
     */
    public static Term data(byte[] bytes, boolean binary) {
        if (binary) {
            return Binary.of(bytes);
        }
        return Str.of(new String(bytes, java.nio.charset.StandardCharsets.UTF_8));
    }

    public static Tuple error(Atom reason) {
        return Tuple.of(ERROR, reason);
    }

    public static Tuple error(Term reason) {
        return Tuple.of(ERROR, reason);
    }

    public static Atom badFileDescriptor() {
        return EBADF;
    }

    public static Atom invalidArgument() {
        return EINVAL;
    }

    public static Atom notSupported() {
        return ENOTSUP;
    }

    /**
     * The Posix error atom for a failed file operation, as OTP reports it.
     */
    public static Atom posix(IOException exception) {
        if (exception instanceof NoSuchFileException
            || exception instanceof FileNotFoundException) {
            return ENOENT;
        }
        if (exception instanceof AccessDeniedException) {
            return EACCES;
        }
        if (exception instanceof FileAlreadyExistsException) {
            return EEXIST;
        }
        if (exception instanceof NotDirectoryException) {
            return ENOTDIR;
        }
        if (exception instanceof DirectoryNotEmptyException) {
            return ENOTEMPTY;
        }
        if (exception instanceof FileSystemException) {
            FileSystemException failure = (FileSystemException) exception;
            String reason = failure.getReason();
            if (reason != null) {
                if (reason.contains("Is a directory")) {
                    return EISDIR;
                }
                if (reason.contains("Not a directory")) {
                    return ENOTDIR;
                }
                if (reason.contains("Read-only file system")) {
                    return EROFS;
                }
                if (reason.contains("No space left")) {
                    return ENOSPC;
                }
            }
        }
        return EIO;
    }

}
