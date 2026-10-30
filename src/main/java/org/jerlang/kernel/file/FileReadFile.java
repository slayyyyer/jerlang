package org.jerlang.kernel.file;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import org.jerlang.erts.erlang.Error;
import org.jerlang.type.Binary;
import org.jerlang.type.List;
import org.jerlang.type.Term;
import org.jerlang.type.Tuple;

public class FileReadFile {

    public static Term dispatch(List params) {
        switch (params.length()) {
        case 1:
            return read_file_1(params.head());
        default:
            throw Error.badarg;
        }
    }

    /**
     * Read the whole file as a binary.
     *
     * http://www.erlang.org/doc/man/file.html#read_file-1
     */
    public static Term read_file_1(Term filename) {
        Path path = Paths.get(FileSupport.path(filename));
        if (!Files.exists(path)) {
            return FileSupport.error(FileSupport.posix(
                new java.nio.file.NoSuchFileException(path.toString())));
        }
        if (Files.isDirectory(path)) {
            return FileSupport.error(FileSupport.posix(
                new java.nio.file.FileSystemException(path.toString(), null, "Is a directory")));
        }
        try {
            return Tuple.of(FileSupport.OK, Binary.of(Files.readAllBytes(path)));
        } catch (IOException e) {
            return FileSupport.error(FileSupport.posix(e));
        }
    }

}
