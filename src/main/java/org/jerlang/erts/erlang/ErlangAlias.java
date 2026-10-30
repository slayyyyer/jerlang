package org.jerlang.erts.erlang;

import org.jerlang.Aliases;
import org.jerlang.ProcessSignals;
import org.jerlang.type.Boolean;
import org.jerlang.type.List;
import org.jerlang.type.Reference;
import org.jerlang.type.Term;

/**
 * erlang:alias/0 creates a reference that can be used as a message
 * destination, and erlang:unalias/1 deactivates it. gen_server uses them for
 * its request identifiers, so a reply can be sent to an alias instead of a
 * pid.
 *
 * http://www.erlang.org/doc/man/erlang.html#alias-0
 * http://www.erlang.org/doc/man/erlang.html#unalias-1
 */
public class ErlangAlias {

    private ErlangAlias() {
    }

    public static Term dispatch(List params) {
        switch (params.length()) {
        case 0:
            return alias_0();
        case 1:
            return unalias_1(params.head().toReference());
        default:
            throw Error.badarg;
        }
    }

    public static Reference alias_0() {
        Reference reference = new Reference();
        Aliases.register(reference, ProcessSignals.self().pid());
        return reference;
    }

    public static Term unalias_1(Reference reference) {
        return Boolean.of(Aliases.deactivate(reference));
    }

}
