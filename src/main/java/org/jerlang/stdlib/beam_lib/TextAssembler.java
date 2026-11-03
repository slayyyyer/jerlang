package org.jerlang.stdlib.beam_lib;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.List;

import org.jerlang.type.Atom;

/**
 * = The Text Assembler
 *
 * Reads the listing {@link Disassembler} writes and produces the file again.
 * The exit test is byte identity over the corpus, so every section has to keep
 * what a reader would consider noise: the chunk order, the sign of an atom
 * count, an atom index that names the same atom twice.
 *
 * The sections that are still hex are copied exactly. The sections that are
 * decoded are written from the numbers in the text, not from the words they
 * stand for: the export table of a real module can contain two atoms with the
 * same name at different indexes, and a writer that resolved names would pick
 * the first one and change the digest. That is the same defect A1 found in
 * {@code TableChunkWriter}, and the text form avoids it by carrying the index.
 */
public final class TextAssembler {

    private TextAssembler() {
    }

    public static byte[] assemble(String text) {
        Lines lines = new Lines(text);
        if (!"beam 1".equals(lines.next())) {
            throw new IllegalArgumentException("not a disassembly: expected 'beam 1'");
        }
        List<BeamFile.ChunkData> chunks = new ArrayList<>();
        while (lines.has()) {
            String header = lines.next();
            String[] fields = header.split(" ");
            if (fields.length != 3 || !"chunk".equals(fields[0])) {
                throw new IllegalArgumentException("expected a chunk header: " + header);
            }
            List<String> body = new ArrayList<>();
            while (true) {
                String line = lines.next();
                if ("end".equals(line)) {
                    break;
                }
                body.add(line);
            }
            byte[] payload = payload(fields[1], body);
            int declared = Integer.parseInt(fields[2]);
            if (payload.length != declared) {
                throw new IllegalArgumentException("chunk " + fields[1] + " declares "
                    + declared + " bytes, the text describes " + payload.length);
            }
            chunks.add(BeamFile.ChunkData.of(fields[1], payload));
        }
        return BeamFile.of(chunks).toBytes();
    }

    private static byte[] payload(String id, List<String> body) {
        if ("Atom".equals(id) || "AtU8".equals(id)) {
            return atoms(id, body);
        }
        if ("ExpT".equals(id) || "ImpT".equals(id) || "LocT".equals(id)) {
            return table(id, body);
        }
        return hex(id, body);
    }

    private static byte[] atoms(String id, List<String> body) {
        int count = Integer.parseInt(first(id, body));
        int size = Math.abs(count);
        AtomChunk chunk = new AtomChunk(new Chunk(ChunkId.of(id), 0, 4), size);
        int position = 1;
        for (int index = 0; index < size; index++) {
            if (position >= body.size()) {
                throw new IllegalArgumentException(id + ": expected " + size
                    + " atoms, found " + index);
            }
            String line = body.get(position++);
            if (!line.startsWith("atom ")) {
                throw new IllegalArgumentException(id + ": expected an atom: " + line);
            }
            String rest = line.substring(5);
            int space = rest.indexOf(' ');
            if (space < 0) {
                throw new IllegalArgumentException(id + ": atom without a name: " + line);
            }
            int number = Integer.parseInt(rest.substring(0, space));
            if (number != index + 1) {
                throw new IllegalArgumentException(id + ": expected atom " + (index + 1)
                    + ", found " + number);
            }
            chunk.set(index, Atom.of(unescape(rest.substring(space + 1))));
        }
        if (position != body.size()) {
            throw new IllegalArgumentException(id + ": " + (body.size() - position)
                + " lines after the last atom");
        }
        return AtomChunkWriter.write(chunk, count < 0);
    }

    private static byte[] table(String id, List<String> body) {
        int count = Integer.parseInt(first(id, body));
        if (body.size() != count + 1) {
            throw new IllegalArgumentException(id + ": declares " + count + " records, has "
                + (body.size() - 1));
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream(4 + count * 12);
        BeamBytes.write4(out, count);
        for (int record = 0; record < count; record++) {
            String[] fields = body.get(record + 1).split(" ");
            if (fields.length != 4) {
                throw new IllegalArgumentException(id + ": expected a three word record: "
                    + body.get(record + 1));
            }
            for (int word = 1; word <= 3; word++) {
                BeamBytes.write4(out, Integer.parseInt(fields[word]));
            }
        }
        return out.toByteArray();
    }

    private static byte[] hex(String id, List<String> body) {
        StringBuilder digits = new StringBuilder();
        for (String line : body) {
            if (!line.startsWith("hex")) {
                throw new IllegalArgumentException(id + ": expected hex data: " + line);
            }
            digits.append(line.length() > 4 ? line.substring(4) : "");
        }
        if (digits.length() % 2 != 0) {
            throw new IllegalArgumentException(id + ": odd number of hex digits");
        }
        byte[] payload = new byte[digits.length() / 2];
        for (int index = 0; index < payload.length; index++) {
            payload[index] = (byte) Integer.parseInt(digits.substring(index * 2, index * 2 + 2), 16);
        }
        return payload;
    }

    private static String first(String id, List<String> body) {
        if (body.isEmpty()) {
            throw new IllegalArgumentException(id + ": empty section");
        }
        String line = body.get(0);
        return line.startsWith("count ") ? line.substring(6) : line;
    }

    static String unescape(String value) {
        StringBuilder text = new StringBuilder(value.length());
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            if (character == '\\' && index + 1 < value.length()) {
                char next = value.charAt(++index);
                switch (next) {
                case 'n':
                    text.append('\n');
                    break;
                case 'r':
                    text.append('\r');
                    break;
                case '\\':
                    text.append('\\');
                    break;
                default:
                    text.append('\\').append(next);
                }
            } else {
                text.append(character);
            }
        }
        return text.toString();
    }

    private static final class Lines {

        private final String[] lines;
        private int position;

        Lines(String text) {
            lines = text.split("\\r?\\n", -1);
        }

        boolean has() {
            while (position < lines.length
                && (lines[position].isEmpty() || lines[position].startsWith("#"))) {
                position++;
            }
            return position < lines.length;
        }

        String next() {
            if (!has()) {
                throw new IllegalArgumentException("unexpected end of disassembly");
            }
            return lines[position++];
        }

    }

}
