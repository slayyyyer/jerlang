package org.jerlang.compiler.lint;

import java.util.Objects;

/**
 * = One lint finding
 *
 * The abstract format keeps annotations as line numbers, not columns, so a
 * diagnostic is line-based. The format is close to erlc's text so a failing
 * build names the same place and the same problem.
 */
public final class Diagnostic {

    public enum Severity {
        ERROR,
        WARNING
    }

    private final Severity severity;
    private final int line;
    private final String message;

    private Diagnostic(Severity severity, int line, String message) {
        this.severity = severity;
        this.line = line;
        this.message = message;
    }

    public static Diagnostic error(int line, String message) {
        return new Diagnostic(Severity.ERROR, line, message);
    }

    public static Diagnostic warning(int line, String message) {
        return new Diagnostic(Severity.WARNING, line, message);
    }

    public Severity severity() {
        return severity;
    }

    public boolean isError() {
        return severity == Severity.ERROR;
    }

    public int line() {
        return line;
    }

    public String message() {
        return message;
    }

    public String format() {
        String prefix = isError() ? "" : "Warning: ";
        return line + ": " + prefix + message;
    }

    @Override
    public boolean equals(Object object) {
        if (!(object instanceof Diagnostic)) {
            return false;
        }
        Diagnostic other = (Diagnostic) object;
        return severity == other.severity
            && line == other.line
            && message.equals(other.message);
    }

    @Override
    public int hashCode() {
        return Objects.hash(severity, line, message);
    }

    @Override
    public String toString() {
        return format();
    }

}
