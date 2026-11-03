package org.jerlang.stdlib.beam_lib;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;

/**
 * = The Assembler
 *
 * Reads a BEAM file and writes it back: the container through {@link BeamFile},
 * each chunk that has a writer through that writer, and every other chunk as
 * the bytes it came in as.
 *
 * The exit test is that the result is the file it started from. That is a
 * stronger requirement than "it still loads", and it is the right one for an
 * assembler: {@code beam_lib:md5/1} hashes chunk payloads, a fun carries the
 * module digest, and a peer that holds the older compile rejects a fun from a
 * module whose digest moved. A writer that is merely semantically correct
 * changes every module it touches.
 *
 * {@code Attr}, {@code CInf}, {@code Abst} and {@code LitT} are kept as they
 * were read; the reasons are in {@link TermChunkWriter}. The chunks nobody
 * interprets at all ({@code Dbgi}, {@code Meta}, {@code Type} and the HiPE
 * chunks) are preserved the same way, which matters because the loader drops
 * them and {@code Meta} is part of the digest.
 */
public final class BeamAssembler {

    private BeamAssembler() {
    }

    public static byte[] assemble(byte[] original) {
        final BeamFile source = BeamFile.read(original);
        BeamFile file = source;

        final AtomChunk atoms = atoms(source);
        final LiteralTableChunk literals = literals(source);

        if (atoms != null) {
            file = file.replace(atoms.id().toString(),
                AtomChunkWriter.write(atoms, source.payload(atoms.id().toString())));
        }
        if (literals != null) {
            file = file.replace("LitT",
                TermChunkWriter.literals(literals, source.payload("LitT")));
        }
        if (source.has("ExpT") && atoms != null) {
            file = file.replace("ExpT", TableChunkWriter.exports(
                read(() -> new ExportTableChunkReader(chunk(source, "ExpT"),
                    stream(source, "ExpT"), atoms).read()), atoms));
        }
        if (source.has("ImpT") && atoms != null) {
            file = file.replace("ImpT", TableChunkWriter.imports(
                read(() -> new ImportTableChunkReader(chunk(source, "ImpT"),
                    stream(source, "ImpT"), atoms).read()), atoms));
        }
        if (source.has("LocT") && atoms != null) {
            file = file.replace("LocT", TableChunkWriter.locals(
                read(() -> new LocalFunctionTableChunkReader(chunk(source, "LocT"),
                    stream(source, "LocT"), atoms).read()), atoms));
        }
        if (source.has("StrT")) {
            file = file.replace("StrT", TableChunkWriter.strings(
                read(() -> new StringTableChunkReader(chunk(source, "StrT"),
                    stream(source, "StrT")).read())));
        }
        if (source.has("FunT") && atoms != null) {
            file = file.replace("FunT", FunctionTableChunkWriter.write(
                read(() -> new FunctionTableChunkReader(chunk(source, "FunT"),
                    stream(source, "FunT"), atoms).read()), atoms));
        }
        if (source.has("Line")) {
            file = file.replace("Line", LineTableChunkWriter.write(
                read(() -> new LineTableChunkReader(chunk(source, "Line"),
                    stream(source, "Line"), atoms).read())));
        }
        if (source.has("Code") && atoms != null) {
            file = file.replace("Code", code(source, atoms, literals));
        }
        return file.toBytes();
    }

    private static byte[] code(BeamFile file, AtomChunk atoms, LiteralTableChunk literals) {
        try {
            return CodeChunkWriter.write(file.payload("Code"), atoms, literals);
        } catch (java.io.IOException ioException) {
            throw new IllegalStateException("can not write the Code chunk", ioException);
        }
    }

    private static AtomChunk atoms(BeamFile file) {
        if (file.has("Atom")) {
            return read(() -> new AtomChunkReader(chunk(file, "Atom"), stream(file, "Atom")).read());
        }
        if (file.has("AtU8")) {
            return read(() -> new AtomChunkReader(chunk(file, "AtU8"), stream(file, "AtU8"), true)
                .read());
        }
        return null;
    }

    private static LiteralTableChunk literals(BeamFile file) {
        if (!file.has("LitT")) {
            return null;
        }
        return read(() -> new LiteralTableChunkReader(chunk(file, "LitT"), stream(file, "LitT"))
            .read());
    }

    private static Chunk chunk(BeamFile file, String id) {
        return new Chunk(ChunkId.of(id), 0, file.payload(id).length);
    }

    private static DataInputStream stream(BeamFile file, String id) {
        return new DataInputStream(new ByteArrayInputStream(file.payload(id)));
    }

    private interface Reader<T> {
        T read() throws Throwable;
    }

    /**
     * A chunk the loader can not read is reported rather than skipped: a writer
     * that silently leaves a chunk out produces a file that loads and is a
     * different module, which is the failure this whole milestone exists to
     * prevent.
     */
    private static <T> T read(Reader<T> reader) {
        try {
            return reader.read();
        } catch (Throwable throwable) {
            throw new IllegalStateException("can not read a chunk while assembling", throwable);
        }
    }

}
