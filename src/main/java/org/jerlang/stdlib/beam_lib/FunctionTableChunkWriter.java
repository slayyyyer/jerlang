package org.jerlang.stdlib.beam_lib;

import java.io.ByteArrayOutputStream;
import java.util.HashMap;
import java.util.Map;

import org.jerlang.type.Atom;

/**
 * Writes the {@code FunT} lambda table: a count followed by six 32 bit fields
 * per lambda. {@code OldUniq} is written as it was read; {@code beam_lib:md5/1}
 * zeroes it before hashing, so it is not part of the module's identity, but a
 * writer that invented a value would still produce a different file.
 */
public final class FunctionTableChunkWriter {

    private FunctionTableChunkWriter() {
    }

    public static byte[] write(FunctionTableChunk chunk, AtomChunk atoms) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        Map<Atom, Integer> index = new HashMap<>();
        Atom[] table = atoms.atoms();
        for (int position = 0; position < table.length; position++) {
            index.putIfAbsent(table[position], position + 1);
        }
        BeamBytes.write4(out, chunk.lambdas().size());
        for (LambdaInfo lambda : chunk.lambdas()) {
            Integer atomIndex = index.get(lambda.function());
            if (atomIndex == null) {
                throw new IllegalArgumentException("lambda function atom not in the table: "
                    + lambda.function());
            }
            BeamBytes.write4(out, atomIndex);
            BeamBytes.write4(out, lambda.arity());
            BeamBytes.write4(out, lambda.label().toInt());
            BeamBytes.write4(out, lambda.index());
            BeamBytes.write4(out, lambda.numFree());
            BeamBytes.write4(out, lambda.oldUnique());
        }
        return out.toByteArray();
    }

}
