package org.jerlang.compiler.preprocess;

/**
 * = A source the preprocessor cannot read
 *
 * Carries the file and the line so a failure names the same position epp would
 * have named, including inside an included header.
 */
public class PreprocessException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final String file;
    private final int line;

    public PreprocessException(String file, int line, String message) {
        super(file + ":" + line + ": " + message);
        this.file = file;
        this.line = line;
    }

    public String file() {
        return file;
    }

    public int line() {
        return line;
    }

}
