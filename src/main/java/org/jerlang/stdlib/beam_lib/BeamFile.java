package org.jerlang.stdlib.beam_lib;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * A BEAM file exactly as it is stored: the IFF container and every chunk in it,
 * in file order, with each payload kept as the bytes that were read.
 *
 * The loader keeps the chunks it interprets and drops the rest. A writer can
 * not do that. {@code beam_lib:md5/1} hashes {@code Meta} when it is present, a
 * fun carries the module's digest on the wire, and a receiving node rejects a
 * digest it does not know. Dropping a chunk that nothing executes therefore
 * still changes the module's identity, and a writer that only writes what the
 * loader understood produces a file no peer will accept a fun from.
 *
 * The payloads are uninterpreted on purpose: this class is the container. The
 * chunk writers that replace a payload with a re-encoded one are separate, and
 * each of them has byte identity against the original as its test.
 */
public final class BeamFile {

    private static final int FOR1 = 0x464f5231; // "FOR1"
    private static final int BEAM = 0x4245414d; // "BEAM"
    private static final int HEADER_SIZE = 12;

    /**
     * One chunk: its four byte identifier and its payload, without the padding
     * that follows the payload in the file.
     */
    public static final class ChunkData {

        private final String id;
        private final byte[] payload;

        private ChunkData(String id, byte[] payload) {
            if (id.length() != 4) {
                throw new IllegalArgumentException("chunk id must be four bytes: " + id);
            }
            this.id = id;
            this.payload = payload;
        }

        public String id() {
            return id;
        }

        public byte[] payload() {
            return payload.clone();
        }

        public int length() {
            return payload.length;
        }

        /**
         * The payload length rounded up to the four byte boundary the format
         * pads to.
         */
        public int paddedLength() {
            return (payload.length + 3) & ~3;
        }

    }

    private final List<ChunkData> chunks;

    private BeamFile(List<ChunkData> chunks) {
        this.chunks = chunks;
    }

    /**
     * Reads a file, keeping every chunk. A chunk whose declared size runs past
     * the end of the file is rejected here rather than when something tries to
     * read it: a truncated file is a broken file, and a writer that accepted it
     * would emit a longer one than it was given.
     */
    public static BeamFile read(byte[] bytes) {
        if (bytes.length < HEADER_SIZE) {
            throw new IllegalArgumentException(
                "not a BEAM file: " + bytes.length + " bytes is shorter than the header");
        }
        if ((bytes[0] & 0xFF) == 0x1f && (bytes[1] & 0xFF) == 0x8b) {
            throw new IllegalArgumentException(
                "compressed BEAM file: decompress it before reading the container");
        }
        if (read4Bytes(bytes, 0) != FOR1) {
            throw new IllegalArgumentException("not a BEAM file: missing FOR1 header");
        }
        int length = read4Bytes(bytes, 4);
        int end = 8 + length;
        if (length < 4 || end > bytes.length) {
            throw new IllegalArgumentException("declared length " + length
                + " exceeds the " + bytes.length + " bytes in the file");
        }
        if (read4Bytes(bytes, 8) != BEAM) {
            throw new IllegalArgumentException("not a BEAM file: missing BEAM header");
        }
        List<ChunkData> chunks = new ArrayList<>();
        int offset = HEADER_SIZE;
        while (offset + 8 <= end) {
            String id = new String(bytes, offset, 4, StandardCharsets.ISO_8859_1);
            int size = read4Bytes(bytes, offset + 4);
            if (size < 0 || offset + 8 + size > end) {
                throw new IllegalArgumentException("chunk " + id + " claims " + size
                    + " bytes, " + (end - offset - 8) + " available");
            }
            byte[] payload = new byte[size];
            System.arraycopy(bytes, offset + 8, payload, 0, size);
            chunks.add(new ChunkData(id, payload));
            offset += 8 + ((size + 3) & ~3);
        }
        return new BeamFile(Collections.unmodifiableList(chunks));
    }

    /**
     * Writes the file back with the container length and the padding
     * recomputed from the payloads, so a replaced chunk of a different size
     * still produces a valid file.
     */
    public byte[] toBytes() {
        int body = 0;
        for (ChunkData chunk : chunks) {
            body += 8 + chunk.paddedLength();
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream(HEADER_SIZE + body);
        write4Bytes(out, FOR1);
        write4Bytes(out, 4 + body);
        write4Bytes(out, BEAM);
        for (ChunkData chunk : chunks) {
            byte[] id = chunk.id.getBytes(StandardCharsets.ISO_8859_1);
            out.write(id, 0, id.length);
            write4Bytes(out, chunk.payload.length);
            out.write(chunk.payload, 0, chunk.payload.length);
            for (int pad = chunk.payload.length; pad % 4 != 0; pad++) {
                out.write(0);
            }
        }
        return out.toByteArray();
    }

    public List<String> chunkIds() {
        List<String> ids = new ArrayList<>(chunks.size());
        for (ChunkData chunk : chunks) {
            ids.add(chunk.id);
        }
        return Collections.unmodifiableList(ids);
    }

    /**
     * The payload of a chunk, or null when the file does not have it.
     */
    public byte[] payload(String id) {
        ChunkData chunk = find(id);
        return chunk == null ? null : chunk.payload();
    }

    /**
     * A copy of this file with one payload replaced. The chunk keeps its
     * position: chunk order is part of the digest for the chunks
     * {@code md5_chunks/0} lists, and a reordering is indistinguishable from a
     * different module to a peer.
     */
    public BeamFile replace(String id, byte[] payload) {
        int index = indexOf(id);
        if (index < 0) {
            throw new IllegalArgumentException("chunk not present: " + id);
        }
        List<ChunkData> replaced = new ArrayList<>(chunks);
        replaced.set(index, new ChunkData(id, payload.clone()));
        return new BeamFile(Collections.unmodifiableList(replaced));
    }

    public boolean has(String id) {
        return indexOf(id) >= 0;
    }

    private ChunkData find(String id) {
        int index = indexOf(id);
        return index < 0 ? null : chunks.get(index);
    }

    private int indexOf(String id) {
        for (int index = 0; index < chunks.size(); index++) {
            if (chunks.get(index).id.equals(id)) {
                return index;
            }
        }
        return -1;
    }

    private static int read4Bytes(byte[] bytes, int offset) {
        return ((bytes[offset] & 0xFF) << 24)
            | ((bytes[offset + 1] & 0xFF) << 16)
            | ((bytes[offset + 2] & 0xFF) << 8)
            | (bytes[offset + 3] & 0xFF);
    }

    private static void write4Bytes(ByteArrayOutputStream out, int value) {
        out.write((value >>> 24) & 0xFF);
        out.write((value >>> 16) & 0xFF);
        out.write((value >>> 8) & 0xFF);
        out.write(value & 0xFF);
    }

}
