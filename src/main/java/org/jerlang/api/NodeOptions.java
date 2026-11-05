package org.jerlang.api;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.jerlang.jvm.HandleTable;

/**
 * = Node options
 *
 * What an embedded node is allowed to do. The defaults are the safe ones: a
 * node that says nothing has no reflection, searches the working directory and
 * a handle table of {@link HandleTable#DEFAULT_MAX_HANDLES}.
 *
 * ----
 * Node node = Node.start(NodeOptions.builder()
 *     .codePath(Path.of("build/ebin"))
 *     .reflection(true)
 *     .build());
 * ----
 */
public final class NodeOptions {

    private final boolean reflection;
    private final int maxHandles;
    private final int schedulers;
    private final List<Path> codePaths;
    private final Path otpRoot;

    private NodeOptions(Builder builder) {
        this.reflection = builder.reflection;
        this.maxHandles = builder.maxHandles;
        this.schedulers = builder.schedulers;
        this.codePaths = List.copyOf(builder.codePaths);
        this.otpRoot = builder.otpRoot;
    }

    public static NodeOptions defaults() {
        return builder().build();
    }

    public static Builder builder() {
        return new Builder();
    }

    /**
     * Whether the node registers the java module. Off by default: without it
     * Erlang code on this node cannot reach arbitrary JVM classes.
     */
    public boolean reflection() {
        return reflection;
    }

    /**
     * How many object handles the node may hand out at once.
     */
    public int maxHandles() {
        return maxHandles;
    }

    /**
     * How many schedulers the node starts, or 0 to follow the host's
     * processor count.
     */
    public int schedulers() {
        return schedulers;
    }

    /**
     * The directories the node puts on its code path, in search order. They are
     * applied after the process-wide path has been reset, so a node never
     * inherits a directory from the node before it.
     */
    public List<Path> codePaths() {
        return codePaths;
    }

    /**
     * The Erlang/OTP installation root, or null when the node does not use an
     * OTP installation. When set, the node adds the {@code kernel} and
     * {@code stdlib} ebins below {@code root/lib} to its code path.
     */
    public Path otpRoot() {
        return otpRoot;
    }

    public static final class Builder {

        private boolean reflection = false;
        private int maxHandles = HandleTable.DEFAULT_MAX_HANDLES;
        private int schedulers = 0;
        private final List<Path> codePaths = new ArrayList<>();
        private Path otpRoot = null;

        private Builder() {
        }

        public Builder reflection(boolean reflection) {
            this.reflection = reflection;
            return this;
        }

        public Builder maxHandles(int maxHandles) {
            if (maxHandles < 1) {
                throw new IllegalArgumentException("maxHandles must be positive: " + maxHandles);
            }
            this.maxHandles = maxHandles;
            return this;
        }

        public Builder schedulers(int schedulers) {
            if (schedulers < 0) {
                throw new IllegalArgumentException("schedulers must not be negative: " + schedulers);
            }
            this.schedulers = schedulers;
            return this;
        }

        /**
         * Add a directory to the node's code path. Directories are searched in
         * the order they were added.
         */
        public Builder codePath(Path directory) {
            if (directory == null) {
                throw new IllegalArgumentException("code path must not be null");
            }
            codePaths.add(directory);
            return this;
        }

        /**
         * Add several directories to the node's code path, keeping their
         * relative order.
         */
        public Builder codePaths(Path... directories) {
            if (directories == null) {
                throw new IllegalArgumentException("code paths must not be null");
            }
            for (Path directory : directories) {
                codePath(directory);
            }
            return this;
        }

        /**
         * Use an Erlang/OTP installation. The root is {@code code:root_dir()},
         * not its {@code lib} directory; the node finds the {@code kernel} and
         * {@code stdlib} ebins below it and fails startup when either is
         * missing.
         */
        public Builder otpRoot(Path root) {
            if (root == null) {
                throw new IllegalArgumentException("OTP root must not be null");
            }
            this.otpRoot = root;
            return this;
        }

        public NodeOptions build() {
            return new NodeOptions(this);
        }
    }

}
