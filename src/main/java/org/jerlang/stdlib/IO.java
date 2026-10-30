package org.jerlang.stdlib;

import org.jerlang.stdlib.io.IOFormat;
import org.jerlang.stdlib.io.IOFwrite;
import org.jerlang.stdlib.io.IOWrite;
import org.jerlang.type.Term;

/**
 * http://erlang.org/doc/man/io.html
 */
public class IO {

    /**
     * Write formatted text to the standard output.
     */
    public static Term format(Term format) {
        return format(format, org.jerlang.type.List.nil);
    }

    /**
     * Write formatted text to the standard output.
     */
    public static Term format(Term format, Term arguments) {
        return IOFormat.dispatch(org.jerlang.type.List.of(format, arguments));
    }

    /**
     * Write formatted text to an io device. Only standard_io and
     * standard_error exist without a file server.
     */
    public static Term format(Term device, Term format, Term arguments) {
        return IOFormat.dispatch(org.jerlang.type.List.of(device, format, arguments));
    }

    public static Term fwrite(Term term) {
        return IOFwrite.fwrite_1(term);
    }

    public static Term write(Term term) {
        return IOWrite.write_1(term);
    }

}
