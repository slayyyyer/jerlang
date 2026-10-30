package org.jerlang.kernel.file;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

import org.jerlang.erts.erlang.Error;
import org.jerlang.type.Atom;
import org.jerlang.type.Integer;
import org.jerlang.type.Term;
import org.jerlang.type.Tuple;

/**
 * The open files behind file:open/2.
 *
 * OTP returns an opaque io device and keeps the descriptor itself in the file
 * server. There is no file server here, so the descriptor is a
 * #file_descriptor record whose third field is the key of an open
 * RandomAccessFile in this registry.
 *
 * A RandomAccessFile is used because it covers every mode file:open offers:
 * it reads, writes, appends and seeks, which is what file:position/2 needs.
 */
public final class FileDescriptors {

    private static final Atom DESCRIPTOR = Atom.of("file_descriptor");
    private static final Atom FILE = Atom.of("file");
    private static final AtomicLong NEXT = new AtomicLong(1);
    private static final Map<Long, Handle> OPEN = new ConcurrentHashMap<>();

    private FileDescriptors() {
    }

    /**
     * An open file. The descriptor methods are synchronized on the handle, so
     * the position and the flags stay consistent when two processes share one
     * io device.
     */
    public static final class Handle {

        private final long id;
        private final String path;
        private final RandomAccessFile file;
        private final boolean binary;
        private final boolean readable;
        private final boolean writable;
        private final boolean append;

        private Handle(long id, String path, RandomAccessFile file,
            boolean binary, boolean readable, boolean writable, boolean append) {
            this.id = id;
            this.path = path;
            this.file = file;
            this.binary = binary;
            this.readable = readable;
            this.writable = writable;
            this.append = append;
        }

        public long id() {
            return id;
        }

        public String path() {
            return path;
        }

        public boolean binary() {
            return binary;
        }

        public boolean readable() {
            return readable;
        }

        public boolean writable() {
            return writable;
        }

        /**
         * Read up to count bytes. Returns null at end of file.
         */
        public synchronized byte[] read(int count) throws IOException {
            if (count == 0) {
                return new byte[0];
            }
            byte[] buffer = new byte[count];
            int read = 0;
            while (read < count) {
                int current = file.read(buffer, read, count - read);
                if (current < 0) {
                    break;
                }
                read += current;
            }
            if (read == 0) {
                return null;
            }
            if (read == count) {
                return buffer;
            }
            byte[] result = new byte[read];
            System.arraycopy(buffer, 0, result, 0, read);
            return result;
        }

        /**
         * Read one line, including the line feed, or null at end of file.
         */
        public synchronized byte[] readLine() throws IOException {
            java.io.ByteArrayOutputStream line = new java.io.ByteArrayOutputStream();
            int current;
            while ((current = file.read()) >= 0) {
                line.write(current);
                if (current == '\n') {
                    break;
                }
            }
            if (line.size() == 0) {
                return null;
            }
            return line.toByteArray();
        }

        public synchronized void write(byte[] bytes) throws IOException {
            if (append) {
                file.seek(file.length());
            }
            file.write(bytes);
        }

        public synchronized long position() throws IOException {
            return file.getFilePointer();
        }

        public synchronized long length() throws IOException {
            return file.length();
        }

        public synchronized long seek(long position) throws IOException {
            file.seek(position);
            return file.getFilePointer();
        }

        public synchronized void close() throws IOException {
            file.close();
        }

    }

    /**
     * Publish an open file and return the descriptor term that names it.
     */
    public static Tuple register(String path, RandomAccessFile file,
        boolean binary, boolean readable, boolean writable, boolean append) {
        long id = NEXT.getAndIncrement();
        OPEN.put(id, new Handle(id, path, file, binary, readable, writable, append));
        return Tuple.of(DESCRIPTOR, FILE, new Integer(id));
    }

    /**
     * The open file a descriptor term names.
     *
     * A descriptor that was closed, or that belongs to another node, raises
     * badarg, the same as any other term file:read/2 does not accept.
     */
    public static Handle handle(Term descriptor) {
        if (!(descriptor instanceof Tuple)) {
            throw Error.badarg;
        }
        Tuple tuple = (Tuple) descriptor;
        if (tuple.arity() != 3
            || !DESCRIPTOR.equals(tuple.element(1))
            || !FILE.equals(tuple.element(2))
            || !(tuple.element(3) instanceof Integer)) {
            throw Error.badarg;
        }
        Handle handle = OPEN.get(tuple.element(3).toInteger().toLong());
        if (handle == null) {
            throw Error.badarg;
        }
        return handle;
    }

    /**
     * Close the file a descriptor names and forget it. Closing twice reports
     * ebadf, as a descriptor that is no longer open has no file behind it.
     */
    public static Term close(Term descriptor) {
        if (!(descriptor instanceof Tuple)) {
            throw Error.badarg;
        }
        Tuple tuple = (Tuple) descriptor;
        if (tuple.arity() != 3
            || !DESCRIPTOR.equals(tuple.element(1))
            || !FILE.equals(tuple.element(2))
            || !(tuple.element(3) instanceof Integer)) {
            throw Error.badarg;
        }
        Handle handle = OPEN.remove(tuple.element(3).toInteger().toLong());
        if (handle == null) {
            return FileSupport.error(FileSupport.badFileDescriptor());
        }
        try {
            handle.close();
            return FileSupport.OK;
        } catch (IOException e) {
            return FileSupport.error(FileSupport.posix(e));
        }
    }

}
