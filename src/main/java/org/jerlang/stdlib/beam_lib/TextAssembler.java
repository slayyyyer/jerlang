package org.jerlang.stdlib.beam_lib;

import java.io.ByteArrayOutputStream;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;

import org.jerlang.Opcode;
import org.jerlang.type.Atom;
import org.jerlang.type.Str;
import org.jerlang.type.Tuple;

/**
 * = The Text Assembler
 *
 * Reads the listing {@link Disassembler} writes and produces the file again.
 * The exit test is byte identity over the corpus, so every section has to keep
 * what a reader would consider noise: the chunk order, the sign of an atom
 * count, an atom index that names the same atom twice, the form and width of
 * an operand.
 *
 * The sections that are written from numbers rather than resolved names are
 * that way for a reason. A real module can have two atoms with the same name at
 * different indexes, and a writer that resolved names would pick the first one
 * and move the digest; the same defect A1 found in {@code TableChunkWriter}.
 * The text form carries the index, so it can not lose that distinction.
 *
 * Term chunks are hex, and copied, because A1 established that re-encoding them
 * is not byte-exact yet: the ETF atom tag differs from what the read side
 * accepts and map pair order does not survive the map.
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
        if ("Code".equals(id)) {
            return code(id, body);
        }
        if ("Line".equals(id)) {
            return line(id, body);
        }
        if ("FunT".equals(id)) {
            return functions(id, body);
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

    /**
     * The header is five copied words. The instructions are an opcode byte and
     * exactly as many operands as the opcode declares, each written through
     * {@link BeamOperand#writeTo} so the encoding the listing recorded is the
     * encoding that comes back.
     */
    private static byte[] code(String id, List<String> body) {
        if (body.isEmpty()) {
            throw new IllegalArgumentException(id + ": missing header");
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        String[] header = body.get(0).split(" ");
        if (header.length != 6 || !"header".equals(header[0])) {
            throw new IllegalArgumentException(id + ": expected a five word header: "
                + body.get(0));
        }
        for (int word = 1; word <= 5; word++) {
            BeamBytes.write4(out, Integer.parseInt(header[word]));
        }
        for (int position = 1; position < body.size(); position++) {
            String line = body.get(position);
            if (line.startsWith("trailing")) {
                byte[] trailing = bytes(line.length() > 9 ? line.substring(9) : "");
                out.write(trailing, 0, trailing.length);
                continue;
            }
            if (!line.startsWith("ins ")) {
                throw new IllegalArgumentException(id + ": expected an instruction: " + line);
            }
            String rest = line.substring(4);
            int space = rest.indexOf(' ');
            if (space < 0) {
                throw new IllegalArgumentException(id + ": instruction without a name: " + line);
            }
            int opcode = Integer.parseInt(rest.substring(0, space));
            Opcode instruction = Opcode.decode(opcode);
            String remainder = rest.substring(space + 1);
            int nameEnd = remainder.indexOf(' ');
            String operands = nameEnd < 0 ? "" : remainder.substring(nameEnd + 1);
            out.write(opcode);
            OperandParser parser = new OperandParser(operands);
            for (int argument = 0; argument < instruction.arity(); argument++) {
                parser.parse().writeTo(out);
            }
        }
        return out.toByteArray();
    }

    private static byte[] line(String id, List<String> body) {
        int version = 0;
        int flags = 0;
        int count = 0;
        List<int[]> records = new ArrayList<>();
        List<String> names = new ArrayList<>();
        for (String text : body) {
            if (text.startsWith("version ")) {
                version = Integer.parseInt(text.substring(8));
            } else if (text.startsWith("flags ")) {
                flags = Integer.parseInt(text.substring(6));
            } else if (text.startsWith("count ")) {
                count = Integer.parseInt(text.substring(6));
            } else if (text.startsWith("filename ")) {
                String rest = text.substring(9);
                int space = rest.indexOf(' ');
                if (space < 0) {
                    throw new IllegalArgumentException(id + ": filename without a name: " + text);
                }
                names.add(unescape(rest.substring(space + 1)));
            } else if (text.startsWith("record ")) {
                String[] fields = text.split(" ");
                if (fields.length != 4) {
                    throw new IllegalArgumentException(id + ": expected a three field record: "
                        + text);
                }
                records.add(new int[] {Integer.parseInt(fields[1]), Integer.parseInt(fields[2]),
                    Integer.parseInt(fields[3])});
            } else {
                throw new IllegalArgumentException(id + ": unknown line line: " + text);
            }
        }
        LineTableChunk chunk = new LineTableChunk(new Chunk(ChunkId.LINE, 0, 0), version, flags,
            count, records.size(), names.size());
        Str[] fnames = chunk.fnames();
        for (int index = 0; index < names.size(); index++) {
            fnames[index] = new Str(names.get(index));
        }
        LineRecord[] target = chunk.records();
        for (int index = 0; index < records.size(); index++) {
            int[] record = records.get(index);
            String name = record[0] < fnames.length && fnames[record[0]] != null
                ? fnames[record[0]].string()
                : "";
            target[index] = new LineRecord(Atom.of(name), record[1], record[0], record[2] != 0);
        }
        return LineTableChunkWriter.write(chunk);
    }

    private static byte[] functions(String id, List<String> body) {
        int count = Integer.parseInt(first(id, body));
        if (body.size() != count + 1) {
            throw new IllegalArgumentException(id + ": declares " + count + " lambdas, has "
                + (body.size() - 1));
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream(4 + count * 24);
        BeamBytes.write4(out, count);
        for (int lambda = 0; lambda < count; lambda++) {
            String[] fields = body.get(lambda + 1).split(" ");
            if (fields.length != 7 || !"lambda".equals(fields[0])) {
                throw new IllegalArgumentException(id + ": expected six lambda fields: "
                    + body.get(lambda + 1));
            }
            for (int word = 1; word <= 6; word++) {
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
        return bytes(digits.toString());
    }

    private static byte[] bytes(String digits) {
        if (digits.length() % 2 != 0) {
            throw new IllegalArgumentException("odd number of hex digits");
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

    /**
     * A recursive parser for the operand syntax. It has to know the same six
     * shapes the reader produces, and nothing else: an unknown shape is a
     * listing that was edited by hand and is refused rather than guessed at.
     */
    private static final class OperandParser {

        private final String text;
        private int position;

        OperandParser(String text) {
            this.text = text;
        }

        BeamOperand parse() {
            expect('(');
            String kind = token();
            BeamOperand operand;
            if ("int".equals(kind)) {
                int tag = number();
                int form = number();
                int width = number();
                BigInteger value = new BigInteger(token());
                operand = BeamOperand.integer(tag, form, value, width,
                    new org.jerlang.type.Integer(value));
            } else if ("list".equals(kind)) {
                BeamOperand count = parse();
                List<BeamOperand> elements = new ArrayList<>();
                while (peek() != ')') {
                    elements.add(parse());
                }
                operand = BeamOperand.list(count, elements.toArray(new BeamOperand[0]),
                    org.jerlang.type.List.nil);
            } else if ("float-register".equals(kind)) {
                int register = number();
                operand = BeamOperand.floatRegister(register,
                    Tuple.of(Atom.of("fr"), org.jerlang.type.Integer.of(register)));
            } else if ("allocation".equals(kind)) {
                BeamOperand count = parse();
                List<BeamOperand> entries = new ArrayList<>();
                while (peek() != ')') {
                    entries.add(parse());
                }
                operand = BeamOperand.allocation(count, entries.toArray(new BeamOperand[0]),
                    org.jerlang.type.List.nil);
            } else if ("literal".equals(kind)) {
                operand = BeamOperand.literal(parse(), org.jerlang.type.List.nil);
            } else if ("typed-register".equals(kind)) {
                BeamOperand register = parse();
                BeamOperand type = parse();
                operand = BeamOperand.typedRegister(register, type, register.term());
            } else {
                throw new IllegalArgumentException("unknown operand: " + kind);
            }
            expect(')');
            return operand;
        }

        private char peek() {
            skip();
            return position < text.length() ? text.charAt(position) : '\0';
        }

        private void expect(char character) {
            skip();
            if (position >= text.length() || text.charAt(position) != character) {
                throw new IllegalArgumentException("expected '" + character + "' in: " + text);
            }
            position++;
        }

        private int number() {
            return Integer.parseInt(token());
        }

        private String token() {
            skip();
            int start = position;
            while (position < text.length() && text.charAt(position) != ' '
                && text.charAt(position) != ')') {
                position++;
            }
            return text.substring(start, position);
        }

        private void skip() {
            while (position < text.length() && text.charAt(position) == ' ') {
                position++;
            }
        }

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
