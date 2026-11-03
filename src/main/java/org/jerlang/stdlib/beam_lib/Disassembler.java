package org.jerlang.stdlib.beam_lib;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;

import org.jerlang.Opcode;
import org.jerlang.type.Atom;
import org.jerlang.type.Str;

/**
 * = The Disassembler
 *
 * Turns a BEAM file into a line-oriented text form. The form exists to be read
 * and diffed: a codegen bug shows up as a change in a listing instead of a
 * digest that moved, and a listing that is checked in is a regression test for
 * every chunk at once.
 *
 * The container, the atom table, the four tables and the code chunk are
 * decoded. The code chunk prints one instruction per line with every operand
 * in a parenthesised form that carries the encoding as well as the value:
 * {@code (int tag form width value)}, {@code (list ...)}, {@code (allocation
 * ...)}, {@code (literal ...)}, {@code (typed-register ...)}. The form matters
 * because the same integer has three encodings and only one reproduces the
 * file.
 *
 * The literal table, the term chunks ({@code Attr}, {@code CInf}, {@code Abst})
 * and the chunks nobody interprets are written as hex. They are copied, not
 * re-encoded, for the reasons A1 recorded: the ETF atom tag and map pair order
 * are not preserved by the readers, and a copy is the only byte-exact answer.
 * The string table is hex for the same reason: it is a blob whose bytes are
 * referenced by offset, not a list of values.
 *
 * The format is line based, because a diff of a line-oriented file is readable
 * and a diff of a nested one is not:
 *
 * [source]
 * ----
 * beam 1
 *
 * chunk AtU8 356
 * count 42
 * atom 1 lists
 * ...
 * end
 *
 * chunk Code 790
 * header 0 0 0 0 250
 * ins 1 label (int 2 0 0 1)
 * ...
 * ins 3 int_code_end
 * end
 * ----
 */
public final class Disassembler {

    private static final int HEX_BYTES_PER_LINE = 32;
    private static final int CODE_HEADER_SIZE = 20;

    private Disassembler() {
    }

    public static String disassemble(byte[] beam) {
        BeamFile file = BeamFile.read(beam);
        AtomChunk atoms = atoms(file);
        LiteralTableChunk literals = literals(file);
        StringBuilder text = new StringBuilder("beam 1\n");
        for (String id : file.chunkIds()) {
            byte[] payload = file.payload(id);
            text.append("\nchunk ").append(id).append(' ').append(payload.length).append('\n');
            if ("Atom".equals(id) || "AtU8".equals(id)) {
                atoms(text, id, payload);
            } else if ("ExpT".equals(id)) {
                table(text, payload, "export");
            } else if ("ImpT".equals(id)) {
                table(text, payload, "import");
            } else if ("LocT".equals(id)) {
                table(text, payload, "local");
            } else if ("Code".equals(id)) {
                code(text, payload, atoms, literals);
            } else if ("Line".equals(id)) {
                line(text, payload, atoms);
            } else if ("FunT".equals(id)) {
                functions(text, payload);
            } else {
                hex(text, payload);
            }
            text.append("end\n");
        }
        return text.toString();
    }

    private static AtomChunk atoms(BeamFile file) {
        try {
            if (file.has("Atom")) {
                return new AtomChunkReader(chunk(file, "Atom"), stream(file, "Atom")).read();
            }
            if (file.has("AtU8")) {
                return new AtomChunkReader(chunk(file, "AtU8"), stream(file, "AtU8"), true).read();
            }
            return null;
        } catch (Throwable throwable) {
            throw new IllegalStateException("can not read the atom chunk", throwable);
        }
    }

    private static LiteralTableChunk literals(BeamFile file) {
        if (!file.has("LitT")) {
            return null;
        }
        try {
            return new LiteralTableChunkReader(chunk(file, "LitT"),
                stream(file, "LitT")).read();
        } catch (Throwable throwable) {
            throw new IllegalStateException("can not read the literal table", throwable);
        }
    }

    private static void atoms(StringBuilder text, String id, byte[] payload) {
        try {
            AtomChunk chunk = new AtomChunkReader(new Chunk(ChunkId.of(id), 0, payload.length),
                new DataInputStream(new ByteArrayInputStream(payload)), "AtU8".equals(id)).read();
            // The sign of the count is part of the format, so it is printed as
            // it was read rather than normalised away.
            text.append("count ").append(BeamBytes.read4(payload, 0)).append('\n');
            Atom[] atoms = chunk.atoms();
            for (int index = 0; index < atoms.length; index++) {
                text.append("atom ").append(index + 1).append(' ')
                    .append(escape(atoms[index].toString())).append('\n');
            }
        } catch (Throwable throwable) {
            throw new IllegalStateException("can not disassemble the " + id + " chunk", throwable);
        }
    }

    private static void table(StringBuilder text, byte[] payload, String verb) {
        int count = BeamBytes.read4(payload, 0);
        text.append("count ").append(count).append('\n');
        for (int record = 0; record < count; record++) {
            int offset = 4 + record * 12;
            text.append(verb).append(' ')
                .append(BeamBytes.read4(payload, offset)).append(' ')
                .append(BeamBytes.read4(payload, offset + 4)).append(' ')
                .append(BeamBytes.read4(payload, offset + 8)).append('\n');
        }
    }

    private static void code(StringBuilder text, byte[] payload, AtomChunk atoms,
        LiteralTableChunk literals) {
        if (payload.length < CODE_HEADER_SIZE) {
            throw new IllegalArgumentException("code chunk is " + payload.length
                + " bytes, shorter than its header");
        }
        text.append("header");
        for (int word = 0; word < 5; word++) {
            text.append(' ').append(BeamBytes.read4(payload, word * 4));
        }
        text.append('\n');
        try {
            DataInputStream input = new DataInputStream(
                new ByteArrayInputStream(payload, CODE_HEADER_SIZE,
                    payload.length - CODE_HEADER_SIZE));
            OperandReader reader = new OperandReader(input);
            while (true) {
                int opcode = reader.read1Byte();
                Opcode instruction = Opcode.decode(opcode);
                text.append("ins ").append(opcode).append(' ').append(instruction);
                for (int argument = 0; argument < instruction.arity(); argument++) {
                    text.append(' ');
                    describe(text, reader.read(atoms, literals));
                }
                text.append('\n');
                if (instruction == Opcode.int_code_end) {
                    break;
                }
            }
            int remaining = input.available();
            if (remaining > 0) {
                text.append("trailing ");
                appendHex(text, payload, payload.length - remaining, payload.length);
                text.append('\n');
            }
        } catch (Throwable throwable) {
            throw new IllegalStateException("can not disassemble the code chunk", throwable);
        }
    }

    /**
     * One operand, with the encoding in front of the value. The tag, the form
     * and the width are what {@link TextAssembler} needs to put the same bytes
     * back; the value alone is not enough.
     */
    private static void describe(StringBuilder text, BeamOperand operand) {
        if (operand.extendedTag() < 0) {
            text.append("(int ").append(operand.tag()).append(' ').append(operand.form())
                .append(' ').append(operand.width()).append(' ').append(operand.value())
                .append(')');
            return;
        }
        switch (operand.extendedTag()) {
        case 1:
            text.append("(list");
            describeChildren(text, operand);
            text.append(')');
            return;
        case 2:
            text.append("(float-register ").append(operand.rawByte()).append(')');
            return;
        case 3:
            text.append("(allocation");
            describeChildren(text, operand);
            text.append(')');
            return;
        case 4:
            text.append("(literal");
            describeChildren(text, operand);
            text.append(')');
            return;
        case 5:
            text.append("(typed-register");
            describeChildren(text, operand);
            text.append(')');
            return;
        default:
            throw new IllegalStateException("unknown extended operand: " + operand.extendedTag());
        }
    }

    private static void describeChildren(StringBuilder text, BeamOperand operand) {
        for (BeamOperand child : operand.children()) {
            text.append(' ');
            describe(text, child);
        }
    }

    private static void line(StringBuilder text, byte[] payload, AtomChunk atoms) {
        try {
            LineTableChunk chunk = new LineTableChunkReader(new Chunk(ChunkId.LINE, 0,
                payload.length), new DataInputStream(new ByteArrayInputStream(payload)),
                atoms).read();
            text.append("version ").append(chunk.version()).append('\n');
            text.append("flags ").append(chunk.flags()).append('\n');
            text.append("count ").append(chunk.count()).append('\n');
            for (LineRecord record : chunk.records()) {
                text.append("record ").append(record.filenameIndex()).append(' ')
                    .append(record.lineNumber()).append(' ')
                    .append(record.explicitFilename() ? 1 : 0).append('\n');
            }
            Str[] fnames = chunk.fnames();
            for (int index = 0; index < fnames.length; index++) {
                text.append("filename ").append(index).append(' ')
                    .append(escape(fnames[index].string())).append('\n');
            }
        } catch (Throwable throwable) {
            throw new IllegalStateException("can not disassemble the line chunk", throwable);
        }
    }

    private static void functions(StringBuilder text, byte[] payload) {
        int count = BeamBytes.read4(payload, 0);
        text.append("count ").append(count).append('\n');
        for (int lambda = 0; lambda < count; lambda++) {
            text.append("lambda");
            for (int word = 0; word < 6; word++) {
                text.append(' ').append(BeamBytes.read4(payload, 4 + lambda * 24 + word * 4));
            }
            text.append('\n');
        }
    }

    private static void hex(StringBuilder text, byte[] payload) {
        if (payload.length == 0) {
            text.append("hex\n");
            return;
        }
        for (int offset = 0; offset < payload.length; offset += HEX_BYTES_PER_LINE) {
            text.append("hex ");
            appendHex(text, payload, offset,
                Math.min(offset + HEX_BYTES_PER_LINE, payload.length));
            text.append('\n');
        }
    }

    private static void appendHex(StringBuilder text, byte[] payload, int from, int to) {
        for (int index = from; index < to; index++) {
            text.append(String.format("%02x", payload[index] & 0xFF));
        }
    }

    private static Chunk chunk(BeamFile file, String id) {
        return new Chunk(ChunkId.of(id), 0, file.payload(id).length);
    }

    private static DataInputStream stream(BeamFile file, String id) {
        return new DataInputStream(new ByteArrayInputStream(file.payload(id)));
    }

    /**
     * An atom can contain anything, including spaces and newlines, so the name
     * is escaped before it goes on a line of its own.
     */
    static String escape(String value) {
        StringBuilder escaped = new StringBuilder(value.length());
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            switch (character) {
            case '\\':
                escaped.append("\\\\");
                break;
            case '\n':
                escaped.append("\\n");
                break;
            case '\r':
                escaped.append("\\r");
                break;
            default:
                escaped.append(character);
            }
        }
        return escaped.toString();
    }

}
