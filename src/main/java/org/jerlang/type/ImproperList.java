package org.jerlang.type;

import java.util.Objects;

/**
 * A list whose tail is not a list, for example [alias | Reference].
 *
 * Erlang/OTP uses improper lists in a few internal protocols, and the
 * external term format can represent them. Ordinary code should not have
 * to deal with this type, but distribution must be able to decode it.
 */
public class ImproperList extends Term {

    private final Term head;
    private final Term tail;

    public ImproperList(Term head, Term tail) {
        this.head = head;
        this.tail = tail;
    }

    public Term head() {
        return head;
    }

    public Term tail() {
        return tail;
    }

    @Override
    public boolean equals(Object object) {
        if (object instanceof ImproperList) {
            ImproperList other = (ImproperList) object;
            return head.equals(other.head) && tail.equals(other.tail);
        }
        return false;
    }

    @Override
    public int hashCode() {
        return Objects.hash(head, tail);
    }

    @Override
    public String toString() {
        return "[" + head + "|" + tail + "]";
    }

}
