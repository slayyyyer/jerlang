package org.jerlang.erts.emulator.op;

import org.jerlang.erts.erlang.Error;
import org.jerlang.type.Str;

/**
 * Native records (OTP 29) are a distinct runtime type. This interpreter has
 * no such values, so the type tests always take their fail label. Constructing
 * one would need its captured field definition and is not supported.
 */
final class NativeRecords {

    private NativeRecords() {
    }

    static Error unsupported() {
        return new Error(Str.of("native records are not supported by this runtime"));
    }

}
