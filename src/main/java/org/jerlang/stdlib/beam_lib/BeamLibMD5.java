package org.jerlang.stdlib.beam_lib;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;

import org.jerlang.type.Atom;
import org.jerlang.type.Binary;
import org.jerlang.type.Integer;
import org.jerlang.type.List;
import org.jerlang.type.Str;
import org.jerlang.type.Term;
import org.jerlang.type.Tuple;

/**
 * Implementation of the beam_lib:md5/1 function.
 *
 * The digest is the MD5 of the data of the chunks that carry code, in the
 * order the md5_chunks/0 function of OTP lists them, not in file order.
 * The OldUniq field of every lambda table record is zeroed first, because
 * OTP does not treat it as part of the module's identity.
 */
public class BeamLibMD5 {

    public static final Atom beam_lib = Atom.of("beam_lib");
    public static final Atom enoent = Atom.of("enoent");
    public static final Atom eperm = Atom.of("eperm");
    public static final Atom error = Atom.of("error");
    public static final Atom file = Atom.of("file");
    public static final Atom file_error = Atom.of("file_error");
    public static final Atom invalid_beam_file = Atom.of("invalid_beam_file");
    public static final Atom not_a_beam_file = Atom.of("not_a_beam_file");

    private static final byte[] ZERO = new byte[4];
    private static final int FUN_TABLE_ENTRY_SIZE = 24;
    private static final int FUN_TABLE_OLD_UNIQ_OFFSET = 20;

    public static Term dispatch(List params) {
        switch (params.length()) {
        case 1:
            return md5_1(params.head().toStr());
        default:
            throw new org.jerlang.erts.erlang.Error("badarg");
        }
    }

    /**
     * The chunks that are significant when calculating the MD5 of a module,
     * listed in the order that OTP hashes them.
     */
    public static List md5_chunks() {
        return List.of(
            ChunkId.ATOM.toStr(),
            ChunkId.ATU8.toStr(),
            ChunkId.CODE.toStr(),
            ChunkId.STRT.toStr(),
            ChunkId.IMPT.toStr(),
            ChunkId.EXPT.toStr(),
            ChunkId.FUNT.toStr(),
            ChunkId.LITT.toStr(),
            Str.of("Meta"),
            Str.of("Recs"),
            Str.of("DbgB"));
    }

    /**
     * Calculates an MD5 redundancy check for the code of the module
     * (compilation date and other attributes are not included).
     */
    public static Term md5_1(Str filename_term) {
        File file = new File(filename_term.string());
        Term result = new List();

        if (!file.exists()) {
            return Tuple.of(error, beam_lib, Tuple.of(file_error, filename_term, enoent));
        }

        if (!file.canRead()) {
            return Tuple.of(error, beam_lib, Tuple.of(file_error, filename_term, eperm));
        }

        try {
            byte[] bytes = Files.readAllBytes(file.toPath());
            result = do_md5(filename_term, bytes);
        } catch (FileNotFoundException fileNotFoundException) {
            return Tuple.of(error, beam_lib, Tuple.of(file_error, filename_term, enoent));
        } catch (IOException ioException) {
            System.err.println("IOException: " + ioException);
        } catch (NoSuchAlgorithmException noSuchAlgorithmException) {
            System.err.println("NoSuchAlgorithmException: " + noSuchAlgorithmException);
        }

        return result;
    }

    private static Term do_md5(Term filename, byte[] bytes)
        throws IOException, NoSuchAlgorithmException {
        Map<String, java.util.List<int[]>> chunks = get_chunks(bytes);
        if (chunks == null) {
            return Tuple.of(error, beam_lib, Tuple.of(invalid_beam_file, filename, Integer.ZERO));
        }
        MessageDigest md5 = init();
        List names = md5_chunks();
        while (names.length() > 0) {
            String name = names.head().toStr().string();
            java.util.List<int[]> found = chunks.get(name);
            if (found != null) {
                for (int[] chunk : found) {
                    update(md5, bytes, chunk[0], chunk[1], name);
                }
            }
            names = names.tail();
        }
        return Tuple.of(
            Atom.of("ok"),
            Tuple.of(
                Atom.of("pid"),
                new Binary(digest(md5))));
    }

    /**
     * The chunk ids and the location of their data in the file. The chunks
     * that OTP does not know are kept too; they are simply never hashed.
     */
    private static Map<String, java.util.List<int[]>> get_chunks(byte[] bytes) {
        if (bytes.length < 12) {
            return null;
        }
        if (read4Bytes(bytes, 0) != 0x464f5231) { // "FOR1"
            return null;
        }
        if (read4Bytes(bytes, 8) != 0x4245414d) { // "BEAM"
            return null;
        }
        int end = Math.min(bytes.length, 8 + read4Bytes(bytes, 4));
        Map<String, java.util.List<int[]>> chunks = new LinkedHashMap<>();
        int offset = 12;
        while (offset + 8 <= end) {
            String name = new String(bytes, offset, 4, StandardCharsets.ISO_8859_1);
            int size = read4Bytes(bytes, offset + 4);
            if (size < 0 || offset + 8 + size > bytes.length) {
                return null;
            }
            chunks.computeIfAbsent(name, key -> new ArrayList<>())
                .add(new int[] { offset + 8, size });
            offset += 8 + size;
            offset += (4 - (size % 4)) % 4;
        }
        return chunks;
    }

    private static void update(MessageDigest md5, byte[] bytes, int offset, int len,
        String name) {
        if ("FunT".equals(name)) {
            updateFunTable(md5, bytes, offset, len);
            return;
        }
        md5.update(bytes, offset, len);
    }

    /**
     * The lambda table starts with a four byte count. Every record is
     * {Function, Arity, Label, Index, NumFree, OldUniq}; OTP zeroes the
     * OldUniq of each record before hashing the chunk.
     */
    private static void updateFunTable(MessageDigest md5, byte[] bytes, int offset, int len) {
        int end = offset + len;
        int position = offset;
        if (end - position >= 4) {
            md5.update(bytes, position, 4);
            position += 4;
        }
        while (end - position >= FUN_TABLE_ENTRY_SIZE) {
            md5.update(bytes, position, FUN_TABLE_OLD_UNIQ_OFFSET);
            md5.update(ZERO, 0, ZERO.length);
            position += FUN_TABLE_ENTRY_SIZE;
        }
        if (position < end) {
            md5.update(bytes, position, end - position);
        }
    }

    private static int read4Bytes(byte[] bytes, int offset) {
        return ((bytes[offset] & 0xFF) << 24)
            | ((bytes[offset + 1] & 0xFF) << 16)
            | ((bytes[offset + 2] & 0xFF) << 8)
            | (bytes[offset + 3] & 0xFF);
    }

    private static MessageDigest init() throws NoSuchAlgorithmException {
        return MessageDigest.getInstance("MD5");
    }

    private static byte[] digest(MessageDigest md5) {
        return md5.digest();
    }

}
