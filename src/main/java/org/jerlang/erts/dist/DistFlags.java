package org.jerlang.erts.dist;

/**
 * Capability flags exchanged during the distribution handshake.
 *
 * See:
 * http://www.erlang.org/doc/apps/erts/erl_dist_protocol.html#distribution-flags
 */
public final class DistFlags {

    /** The node is to be published and part of the global namespace. */
    public static final long PUBLISHED = 0x01;

    /** The node implements extended (3 x 32 bit) references. Mandatory. */
    public static final long EXTENDED_REFERENCES = 0x04;

    /** The node implements distributed process monitoring. */
    public static final long DIST_MONITOR = 0x08;

    /** The node implements distributed monitoring of registered names. */
    public static final long DIST_MONITOR_NAME = 0x20;

    /** The node uses separate tags for funs. Mandatory. */
    public static final long FUN_TAGS = 0x10;

    /** The node understands NEW_FUN_EXT. Mandatory. */
    public static final long NEW_FUN_TAGS = 0x80;

    /** The node can handle extended pids and ports. Mandatory. */
    public static final long EXTENDED_PIDS_PORTS = 0x100;

    /** The node understands EXPORT_EXT. Mandatory. */
    public static final long EXPORT_PTR_TAG = 0x200;

    /** The node understands BIT_BINARY_EXT. Mandatory. */
    public static final long BIT_BINARIES = 0x400;

    /** The node understands NEW_FLOAT_EXT. Mandatory. */
    public static final long NEW_FLOATS = 0x800;

    /** The node implements an atom cache in the distribution header. */
    public static final long DIST_HDR_ATOM_CACHE = 0x2000;

    /** The node understands SMALL_ATOM_EXT. */
    public static final long SMALL_ATOM_TAGS = 0x4000;

    /** The node understands UTF-8 encoded atoms. Mandatory. */
    public static final long UTF8_ATOMS = 0x10000;

    /** The node understands maps. Mandatory. */
    public static final long MAP_TAG = 0x20000;

    /** The node understands NEW_PID_EXT and NEWER_REFERENCE_EXT. Mandatory. */
    public static final long BIG_CREATION = 0x40000;

    /** The node supports the version 6 handshake. Mandatory. */
    public static final long HANDSHAKE_23 = 0x1000000;

    /** The node supports the new link protocol. Mandatory as of OTP 26. */
    public static final long UNLINK_ID = 0x2000000;

    /** The node supports the capabilities that are mandatory in OTP 25. */
    public static final long MANDATORY_25_DIGEST = 0x4000000;

    /** The node supports SPAWN_REQUEST and SPAWN_REPLY. */
    public static final long SPAWN = 0x100000000L;

    /** The node accepts 32 bit pids and up to 5 word references. */
    public static final long V4_NC = 0x400000000L;

    /**
     * The flags that are mandatory for a node that wants to talk to
     * Erlang/OTP 26 and later.
     */
    public static final long MANDATORY = EXTENDED_REFERENCES
        | FUN_TAGS
        | NEW_FUN_TAGS
        | EXTENDED_PIDS_PORTS
        | EXPORT_PTR_TAG
        | BIT_BINARIES
        | NEW_FLOATS
        | UTF8_ATOMS
        | MAP_TAG
        | BIG_CREATION
        | HANDSHAKE_23
        | UNLINK_ID
        | MANDATORY_25_DIGEST
        | V4_NC;

    /**
     * The flags JErlang presents. It does not include
     * DIST_HDR_ATOM_CACHE, so the peer sends control messages in the
     * old 112-tagged format, which does not use an atom cache.
     */
    public static final long OURS = PUBLISHED | MANDATORY | DIST_MONITOR | DIST_MONITOR_NAME | SPAWN;

    /**
     * The flags a node presents. A hidden node clears DFLAG_PUBLISHED, so
     * the peer knows that this connection is not part of the visible node
     * set, exactly as OTP's hidden nodes do.
     */
    public static long ours(boolean hidden) {
        return hidden ? OURS & ~PUBLISHED : OURS;
    }

    private DistFlags() {
    }

}
