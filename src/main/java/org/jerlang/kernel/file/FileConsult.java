package org.jerlang.kernel.file;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import org.jerlang.erts.erlang.Error;
import org.jerlang.type.Atom;
import org.jerlang.type.Integer;
import org.jerlang.type.List;
import org.jerlang.type.Str;
import org.jerlang.type.Term;
import org.jerlang.type.Tuple;

public class FileConsult {

    private static final Atom ERL_PARSE = Atom.of("erl_parse");

    public static Term dispatch(List params) {
        switch (params.length()) {
        case 1:
            return consult_1(params.head());
        default:
            throw Error.badarg;
        }
    }

    /**
     * Read every term in a file. A syntax error is reported the way OTP
     * reports it, with the line the parser stopped on.
     *
     * http://www.erlang.org/doc/man/file.html#consult-1
     */
    public static Term consult_1(Term filename) {
        Path path = Paths.get(FileSupport.path(filename));
        if (!Files.exists(path)) {
            return FileSupport.error(FileSupport.posix(
                new java.nio.file.NoSuchFileException(path.toString())));
        }
        if (Files.isDirectory(path)) {
            return FileSupport.error(FileSupport.posix(
                new java.nio.file.FileSystemException(path.toString(), null, "Is a directory")));
        }
        String text;
        try {
            text = new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
        } catch (IOException e) {
            return FileSupport.error(FileSupport.posix(e));
        }
        try {
            return Tuple.of(FileSupport.OK, TermReader.parseAll(text));
        } catch (TermReader.SyntaxError e) {
            return FileSupport.error(Tuple.of(Integer.of(e.line()), ERL_PARSE,
                Str.of(e.getMessage())));
        }
    }

}
