package org.jerlang.compiler.scan;

/**
 * = A source the scanner cannot read
 *
 * The position is one based, like the line annotation on a token, so a message
 * points at the same line a form or a stacktrace would name. The column is the
 * character the scanner stopped at, not the one after it.
 */
public class ScanException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final int line;
    private final int column;

    public ScanException(int line, int column, String message) {
        super("line " + line + ", column " + column + ": " + message);
        this.line = line;
        this.column = column;
    }

    public int line() {
        return line;
    }

    public int column() {
        return column;
    }

}
