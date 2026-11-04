package org.jerlang.stdlib.beam_lib;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.jerlang.FunctionSignature;

/**
 * = Local Function Table Chunk
 *
 * The Local Function Table Chunk is used to define methods that this module
 * defines, but does not export.
 *
 * The data format is equal to the data format of the Export Table Chunk.
 */
public class LocalFunctionTableChunk extends Chunk {

    private final ArrayList<FunctionSignature> locals;

    public LocalFunctionTableChunk(Chunk chunk) {
        super(ChunkId.LOCT, chunk);
        locals = new ArrayList<>();
    }

    public void add(FunctionSignature functionSignature) {
        locals.add(functionSignature);
    }

    /**
     * The local functions as an Erlang list. {@code List.of} builds its result
     * by prepending, so this list is in reverse file order; it exists for the
     * interpreter, which looks records up by signature and does not care.
     */
    public org.jerlang.type.List locals() {
        return org.jerlang.type.List.of(locals);
    }

    /**
     * The local functions in file order, for the writer. The order is part of
     * the chunk: a table written backwards is the same table to the loader and
     * a different chunk to {@code beam_lib:md5/1}.
     */
    public List<FunctionSignature> records() {
        return Collections.unmodifiableList(locals);
    }

    @Override
    public String toString() {
        StringBuilder stringBuilder = new StringBuilder();
        stringBuilder.append("{local_functions_chunk,[");
        for (FunctionSignature s : locals) {
            stringBuilder.append(s).append(',');
        }
        if (locals.size() > 0) {
            stringBuilder.setLength(stringBuilder.length() - 1);
        }
        stringBuilder.append("]}");
        return stringBuilder.toString();
    }

}
