package org.jerlang.stdlib.beam_lib;

import java.io.ByteArrayOutputStream;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.jerlang.FunctionSignature;
import org.jerlang.type.Atom;

/**
 * Writes {@code ExpT}, {@code ImpT}, {@code LocT} and {@code StrT}.
 *
 * The three function tables are arrays of 32 bit words whose first fields are
 * atom indexes. The loader resolves those indexes to atoms while reading, so
 * the writer has to find them again; a function name that is not in the atom
 * table means the tables disagree, which is reported rather than written as
 * zero, because index zero is the null atom, not "missing".
 *
 * The order of the records is kept. The loader does not depend on it, but the
 * digest does, and a table written in a different order is a different chunk
 * with the same meaning.
 */
public final class TableChunkWriter {

    private TableChunkWriter() {
    }

    public static byte[] exports(ExportTableChunk chunk, AtomChunk atoms) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        Map<Atom, Integer> index = index(atoms);
        BeamBytes.write4(out, chunk.exports().size());
        for (FunctionSignature signature : chunk.exports()) {
            BeamBytes.write4(out, atomIndex(index, signature.function()));
            BeamBytes.write4(out, signature.fun_arity().toInt());
            BeamBytes.write4(out, signature.label().toInt());
        }
        return out.toByteArray();
    }

    public static byte[] imports(ImportTableChunk chunk, AtomChunk atoms) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        Map<Atom, Integer> index = index(atoms);
        BeamBytes.write4(out, chunk.imports().size());
        for (FunctionSignature signature : chunk.imports()) {
            BeamBytes.write4(out, atomIndex(index, signature.module()));
            BeamBytes.write4(out, atomIndex(index, signature.function()));
            BeamBytes.write4(out, signature.fun_arity().toInt());
        }
        return out.toByteArray();
    }

    public static byte[] locals(LocalFunctionTableChunk chunk, AtomChunk atoms) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        Map<Atom, Integer> index = index(atoms);
        List<FunctionSignature> locals = chunk.records();
        BeamBytes.write4(out, locals.size());
        for (FunctionSignature signature : locals) {
            BeamBytes.write4(out, atomIndex(index, signature.function()));
            BeamBytes.write4(out, signature.fun_arity().toInt());
            BeamBytes.write4(out, signature.label().toInt());
        }
        return out.toByteArray();
    }

    public static byte[] strings(StringTableChunk chunk) {
        return chunk.strings().toByteArray();
    }

    private static Map<Atom, Integer> index(AtomChunk atoms) {
        Map<Atom, Integer> index = new HashMap<>();
        Atom[] table = atoms.atoms();
        for (int position = 0; position < table.length; position++) {
            // Index zero is the null atom, so the table is one based. The first
            // occurrence wins: a duplicate name would be a compiler bug, and
            // the first index is the one the rest of the file refers to.
            index.putIfAbsent(table[position], position + 1);
        }
        return index;
    }

    private static int atomIndex(Map<Atom, Integer> index, Atom atom) {
        Integer position = index.get(atom);
        if (position == null) {
            throw new IllegalArgumentException("atom not in the atom table: " + atom);
        }
        return position;
    }

}
