package org.jerlang.compiler;

/**
 * A parse transform could not be found or did not return a form list. The line
 * is the line of the {@code -compile} attribute that named it.
 */
final class ParseTransformException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final int line;

    ParseTransformException(int line, String message) {
        super(message);
        this.line = line;
    }

    ParseTransformException(int line, String message, Throwable cause) {
        super(message, cause);
        this.line = line;
    }

    int line() {
        return line;
    }

}
