package org.jerlang.type;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;

/**
 * The Erlang term order, used by the comparison operators and the
 * comparison instructions.
 *
 * The order between types is:
 * number &lt; atom &lt; reference &lt; fun &lt; port &lt; pid &lt; tuple
 * &lt; map &lt; nil &lt; list &lt; bitstring.
 *
 * Within a type, numbers compare by value regardless of integer or float,
 * atoms by name, tuples first by arity and then element by element, maps
 * first by size and then by keys in term order, lists element by element
 * (nil is smaller than any non empty list), and bitstrings byte by byte.
 *
 * References compare by node, creation and id; ports by node, creation and
 * port id; pids by node, creation, process id and serial. Funs compare by
 * their signature. OTP compares those by internal table order, which is not
 * observable from Erlang code, so a stable order is enough here.
 */
public final class TermOrder {

    private static final int NUMBER = 0;
    private static final int ATOM = 1;
    private static final int REFERENCE = 2;
    private static final int FUN = 3;
    private static final int PORT = 4;
    private static final int PID = 5;
    private static final int TUPLE = 6;
    private static final int MAP = 7;
    private static final int NIL = 8;
    private static final int LIST = 9;
    private static final int BITSTRING = 10;

    private static final Comparator<Term> TERM_ORDER = new Comparator<Term>() {
        @Override
        public int compare(Term a, Term b) {
            return TermOrder.compare(a, b);
        }
    };

    private TermOrder() {
    }

    public static int compare(Term a, Term b) {
        int rankA = rank(a);
        int rankB = rank(b);
        if (rankA != rankB) {
            return java.lang.Integer.compare(rankA, rankB);
        }
        switch (rankA) {
        case NUMBER:
            return a.toNumber().toBigDecimal().compareTo(b.toNumber().toBigDecimal());
        case ATOM:
            return a.toAtom().toString().compareTo(b.toAtom().toString());
        case REFERENCE:
            return compareReferences(a.toReference(), b.toReference());
        case FUN:
            return a.toString().compareTo(b.toString());
        case PORT:
            return comparePorts(a.toPortID(), b.toPortID());
        case PID:
            return comparePids(a.toPID(), b.toPID());
        case TUPLE:
            return compareTuples(a.toTuple(), b.toTuple());
        case MAP:
            return compareMaps(a.toMap(), b.toMap());
        case NIL:
            return 0;
        case LIST:
            return compareLists(a.toList(), b.toList());
        case BITSTRING:
            return compareBitStrings(a.toBitString(), b.toBitString());
        default:
            return 0;
        }
    }

    /**
     * The Erlang == operator: numeric comparison across integers and floats,
     * structural equality for everything else.
     */
    public static boolean equal(Term a, Term b) {
        if (a instanceof Number && b instanceof Number) {
            return compare(a, b) == 0;
        }
        return a.equals(b);
    }

    private static int rank(Term term) {
        if (term instanceof Number) {
            return NUMBER;
        } else if (term instanceof Atom) {
            return ATOM;
        } else if (term instanceof Reference) {
            return REFERENCE;
        } else if (term instanceof Fun) {
            return FUN;
        } else if (term instanceof PortID) {
            return PORT;
        } else if (term instanceof PID) {
            return PID;
        } else if (term instanceof Tuple) {
            return TUPLE;
        } else if (term instanceof Map) {
            return MAP;
        } else if (term instanceof Nil) {
            return NIL;
        } else if (term instanceof List) {
            return LIST;
        } else {
            return BITSTRING;
        }
    }

    private static int compareReferences(Reference a, Reference b) {
        int result = compareAtoms(a.node(), b.node());
        if (result != 0) {
            return result;
        }
        result = java.lang.Integer.compare(a.creation(), b.creation());
        if (result != 0) {
            return result;
        }
        return a.id().compareTo(b.id());
    }

    private static int comparePorts(PortID a, PortID b) {
        int result = compareAtoms(a.node(), b.node());
        if (result != 0) {
            return result;
        }
        result = java.lang.Integer.compare(a.creation(), b.creation());
        if (result != 0) {
            return result;
        }
        return java.lang.Integer.compare(a.processId(), b.processId());
    }

    private static int comparePids(PID a, PID b) {
        int result = compareAtoms(a.node(), b.node());
        if (result != 0) {
            return result;
        }
        result = java.lang.Integer.compare(a.creation(), b.creation());
        if (result != 0) {
            return result;
        }
        result = java.lang.Integer.compare(a.processId(), b.processId());
        if (result != 0) {
            return result;
        }
        return java.lang.Integer.compare(a.serial(), b.serial());
    }

    private static int compareAtoms(Atom a, Atom b) {
        return a.toString().compareTo(b.toString());
    }

    private static int compareTuples(Tuple a, Tuple b) {
        int result = java.lang.Integer.compare(a.arity(), b.arity());
        if (result != 0) {
            return result;
        }
        for (int index = 1; index <= a.arity(); index++) {
            result = compare(a.element(index), b.element(index));
            if (result != 0) {
                return result;
            }
        }
        return 0;
    }

    private static int compareMaps(Map a, Map b) {
        int result = java.lang.Integer.compare(a.size().toInt(), b.size().toInt());
        if (result != 0) {
            return result;
        }
        ArrayList<Term> keysA = sortedKeys(a);
        ArrayList<Term> keysB = sortedKeys(b);
        for (int index = 0; index < keysA.size(); index++) {
            Term keyA = keysA.get(index);
            Term keyB = keysB.get(index);
            result = compare(keyA, keyB);
            if (result != 0) {
                return result;
            }
            result = compare(a.get(keyA), b.get(keyB));
            if (result != 0) {
                return result;
            }
        }
        return 0;
    }

    private static ArrayList<Term> sortedKeys(Map map) {
        ArrayList<Term> keys = new ArrayList<>();
        List list = map.keys();
        while (list.length() > 0) {
            keys.add(list.head());
            list = list.tail();
        }
        Collections.sort(keys, TERM_ORDER);
        return keys;
    }

    private static int compareLists(List a, List b) {
        List left = a;
        List right = b;
        while (left.length() > 0 && right.length() > 0) {
            int result = compare(left.head(), right.head());
            if (result != 0) {
                return result;
            }
            left = left.tail();
            right = right.tail();
        }
        // A proper prefix sorts before the longer list, which also covers
        // nil, the smallest list.
        return java.lang.Integer.compare(left.length(), right.length());
    }

    private static int compareBitStrings(BitString a, BitString b) {
        int[] bytesA = a.bytes();
        int[] bytesB = b.bytes();
        int common = Math.min(bytesA.length, bytesB.length);
        for (int index = 0; index < common; index++) {
            int result = java.lang.Integer.compare(bytesA[index] & 0xFF, bytesB[index] & 0xFF);
            if (result != 0) {
                return result;
            }
        }
        int result = java.lang.Integer.compare(bytesA.length, bytesB.length);
        if (result != 0) {
            return result;
        }
        return java.lang.Integer.compare(a.bits(), b.bits());
    }

}
