package org.jerlang.kernel.file;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.jerlang.erts.erlang.Error;
import org.jerlang.type.Term;
import org.jerlang.type.Tuple;

public class FileListDir {

    public static Term dispatch(org.jerlang.type.List params) {
        switch (params.length()) {
        case 1:
            return list_dir_1(params.head());
        default:
            throw Error.badarg;
        }
    }

    /**
     * List the names of the entries in a directory, without "." and "..".
     *
     * The names are sorted, which OTP does not promise, so callers that only
     * look entries up are unaffected and a test can compare a list.
     *
     * http://www.erlang.org/doc/man/file.html#list_dir-1
     */
    public static Term list_dir_1(Term directory) {
        Path path = Paths.get(FileSupport.path(directory));
        if (!Files.exists(path)) {
            return FileSupport.error(FileSupport.posix(
                new java.nio.file.NoSuchFileException(path.toString())));
        }
        if (!Files.isDirectory(path)) {
            return FileSupport.error(FileSupport.posix(
                new java.nio.file.NotDirectoryException(path.toString())));
        }
        List<String> names = new ArrayList<>();
        try (java.util.stream.Stream<Path> entries = Files.list(path)) {
            entries.forEach(entry -> names.add(entry.getFileName().toString()));
        } catch (IOException e) {
            return FileSupport.error(FileSupport.posix(e));
        }
        Collections.sort(names);
        org.jerlang.type.List result = org.jerlang.type.List.nil;
        for (int index = names.size() - 1; index >= 0; index--) {
            result = new org.jerlang.type.List(org.jerlang.type.Str.of(names.get(index)), result);
        }
        return Tuple.of(FileSupport.OK, result);
    }

}
