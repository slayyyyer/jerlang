package org.jerlang.stdlib.beam_lib;

import org.jerlang.type.Atom;

/**
 * One entry of the line table: the source file and line of one code position.
 *
 * The index into the chunk's own filename table and whether the record carried
 * the {@code a} tagged index are kept because the encoding omits the index when
 * it has not changed. Two records with the same filename can come from an
 * omitted index and a written one, and only the second is a byte longer; a
 * writer that always wrote it would grow the chunk and move the module digest.
 */
public class LineRecord {

    private final Atom filename;
    private final int lineNumber;
    private final int filenameIndex;
    private final boolean explicitFilename;

    public LineRecord(Atom filename, int lineNumber) {
        this(filename, lineNumber, 0, true);
    }

    public LineRecord(Atom filename, int lineNumber, int filenameIndex, boolean explicitFilename) {
        this.filename = filename;
        this.lineNumber = lineNumber;
        this.filenameIndex = filenameIndex;
        this.explicitFilename = explicitFilename;
    }

    public Atom filename() {
        return filename;
    }

    public int filenameIndex() {
        return filenameIndex;
    }

    public boolean explicitFilename() {
        return explicitFilename;
    }

    public int lineNumber() {
        return lineNumber;
    }

    @Override
    public String toString() {
        return new StringBuilder()
            .append("{line_record,")
            .append(filename)
            .append(',')
            .append(lineNumber)
            .append('}')
            .toString();
    }

}
