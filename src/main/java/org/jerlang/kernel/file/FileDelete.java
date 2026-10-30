package org.jerlang.kernel.file;

import jnr.posix.POSIX;
import jnr.posix.POSIXFactory;

import org.jerlang.erts.erlang.Error;
import org.jerlang.type.Atom;
import org.jerlang.type.Integer;
import org.jerlang.type.List;
import org.jerlang.type.Str;
import org.jerlang.type.Term;
import org.jerlang.type.Tuple;

public class FileDelete {

    private static final Atom error = Atom.of("error");
    private static final Atom ok = Atom.of("ok");
    private static final POSIX posix;

    static {
        posix = POSIXFactory.getPOSIX();
    }

    public static Term dispatch(List params) {
        switch (params.length()) {
        case 1:
            // A path is any string, and the one ++ builds is a plain list,
            // not the Str a literal loads as.
            return delete(FileSupport.path(params.head()));
        default:
            throw Error.badarg;
        }
    }

    /**
     * http://www.erlang.org/doc/man/file.html#delete-1
     */
    public static Term delete_1(Str filename) {
        return delete(filename.string());
    }

    private static Term delete(String filename) {
        int result = posix.unlink(filename);
        switch (result) {
        case 0:
            return ok;
        default:
            return Tuple.of(error, Integer.of(result));
        }
    }

}
