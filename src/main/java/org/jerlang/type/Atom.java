package org.jerlang.type;

import static java.nio.charset.StandardCharsets.ISO_8859_1;

import org.jerlang.AtomRegistry;

/**
 * An atom is a literal, a constant with name.
 *
 * An atom is to be enclosed in single quotes (')
 * if it does not begin with a lower-case letter or
 * if it contains other characters than alphanumeric
 * characters, underscore (_), or @.
 *
 * http://www.erlang.org/doc/reference_manual/data_types.html
 */
public class Atom extends Term {

    private final String name;
    private final String value;

    private Atom(String name) {
        this(name, name);
    }

    private Atom(String name, String value) {
        this.name = name;
        this.value = value;
    }

    @Override
    public boolean equals(Object object) {
        if (object instanceof Atom) {
            Atom other = (Atom) object;
            return name.equals(other.name) && value.equals(other.value);
        }
        return false;
    }

    @Override
    public int hashCode() {
        return name.hashCode();
    }

    public boolean isFalse() {
        if (equals(Boolean.am_false)) {
            return true;
        } else if (equals(Boolean.am_true)) {
            return false;
        } else {
            throw new Error("Cannot convert to boolean: " + this);
        }
    }

    public boolean isTrue() {
        if (equals(Boolean.am_false)) {
            return false;
        } else if (equals(Boolean.am_true)) {
            return true;
        } else {
            throw new Error("Cannot convert to boolean: " + this);
        }
    }

    @Override
    public Atom toAtom() {
        return this;
    }

    @Override
    public String toString() {
        return name;
    }

    public static Atom of(byte[] bytes) {
        return of(new String(bytes, ISO_8859_1));
    }

    public static Atom of(String string) {
        AtomRegistry registry = AtomRegistry.instance();
        Atom atom = registry.get(string);
        if (atom != null) {
            return atom;
        }
        Atom candidate = new Atom(string);
        Atom previous = registry.putIfAbsent(candidate);
        return previous == null ? candidate : previous;
    }

    public static Atom of(String name, String value) {
        AtomRegistry registry = AtomRegistry.instance();
        Atom atom = registry.get(name);
        if (atom != null) {
            if (atom.value.equals(value)) {
                return atom;
            }
            throw new Error("Duplicate value of atom: " + name);
        }
        Atom candidate = new Atom(name, value);
        Atom previous = registry.putIfAbsent(candidate);
        if (previous == null) {
            return candidate;
        }
        if (previous.value.equals(value)) {
            return previous;
        }
        throw new Error("Duplicate value of atom: " + name);
    }

}
