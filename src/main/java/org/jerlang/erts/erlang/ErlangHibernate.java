package org.jerlang.erts.erlang;

import org.jerlang.type.Atom;
import org.jerlang.type.List;
import org.jerlang.type.Term;

/**
 * erlang:hibernate/0.
 *
 * gen_server calls this when its hibernate_after timeout expires. The
 * runtime has no separate hibernated process state, so this is a no-op: the
 * caller's next receive blocks until a message arrives and leaves that
 * message in the mailbox, which is what gen_server:loop_wakeup expects.
 *
 * http://www.erlang.org/doc/man/erlang.html#hibernate-0
 */
public class ErlangHibernate {

    private ErlangHibernate() {
    }

    public static Term dispatch(List params) {
        switch (params.length()) {
        case 0:
            return hibernate_0();
        default:
            throw Error.badarg;
        }
    }

    public static Term hibernate_0() {
        return Atom.of("ok");
    }

}
