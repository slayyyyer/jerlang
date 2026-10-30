package org.jerlang.stdlib.ets;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;

import org.jerlang.erts.erlang.Error;
import org.jerlang.type.Atom;
import org.jerlang.type.Binary;
import org.jerlang.type.BitString;
import org.jerlang.type.Boolean;
import org.jerlang.type.Fun;
import org.jerlang.type.ImproperList;
import org.jerlang.type.Integer;
import org.jerlang.type.List;
import org.jerlang.type.Map;
import org.jerlang.type.Nil;
import org.jerlang.type.Number;
import org.jerlang.type.PID;
import org.jerlang.type.Reference;
import org.jerlang.type.Str;
import org.jerlang.type.Term;
import org.jerlang.type.TermOrder;
import org.jerlang.type.Tuple;

/**
 * An ETS match specification: a list of `{Pattern, Guards, Body}` clauses.
 *
 * Patterns understand `'_'` (any term), `'$N'` (bind variable N, and a second
 * occurrence has to be the same term) and `'$_'` (the whole object). Bodies
 * substitute those variables, `'$$'` and `'$_'` expand to the whole object,
 * and everything else is rebuilt. Guards accept a subset of the guard
 * language: comparisons, `andalso`/`orelse`/`not`, the type tests, and
 * `element`, `size`, `length`, `hd`, `tl` and the size functions. A guard
 * outside that subset raises an error instead of silently matching nothing,
 * because a select that quietly drops rows is worse than one that fails.
 *
 * http://www.erlang.org/doc/apps/erts/match_spec.html
 */
public final class MatchSpec {

    private static final Atom WILDCARD = Atom.of("_");
    private static final Atom WHOLE = Atom.of("$_");
    private static final Atom WHOLE_OBJECT = Atom.of("$$");

    private static final class Clause {

        private final Term pattern;
        private final Term guards;
        private final Term body;

        private Clause(Term pattern, Term guards, Term body) {
            this.pattern = pattern;
            this.guards = guards;
            this.body = body;
        }

    }

    private final java.util.List<Clause> clauses = new ArrayList<>();

    private MatchSpec() {
    }

    /**
     * Compile a match specification. A specification that is not a list of
     * `{Pattern, Guards, Body}` triples is badarg, as in OTP.
     */
    public static MatchSpec compile(Term specification) {
        MatchSpec spec = new MatchSpec();
        if (!(specification instanceof List) || isNil(specification)) {
            throw Error.badarg;
        }
        List entries = (List) specification;
        while (entries.length() > 0) {
            Term entry = entries.head();
            if (!(entry instanceof Tuple) || ((Tuple) entry).arity() != 3) {
                throw Error.badarg;
            }
            Tuple clause = (Tuple) entry;
            spec.clauses.add(new Clause(clause.element(1), clause.element(2), clause.element(3)));
            entries = entries.tail();
        }
        return spec;
    }

    /**
     * A specification with a single clause `{Pattern, [], []}`, which is what
     * ets:match/2,3 and ets:match_object/2,3 compile their argument to. Those
     * functions return the pattern variables or the object, not a body.
     */
    public static MatchSpec forPattern(Term pattern) {
        MatchSpec spec = new MatchSpec();
        spec.clauses.add(new Clause(pattern, List.nil, List.nil));
        return spec;
    }

    /**
     * The body of the first clause that matches, or null when no clause does.
     * A clause's guard list has to hold, and an empty list is true.
     */
    public Term select(Term object) {
        for (Clause clause : clauses) {
            java.util.Map<java.lang.Integer, Term> bindings = new HashMap<>();
            if (!match(clause.pattern, object, bindings)) {
                continue;
            }
            if (!guards(clause.guards, bindings, object)) {
                continue;
            }
            return expand(clause.body, bindings, object);
        }
        return null;
    }

    /**
     * The values bound to `$1` .. `$N` in the first matching clause, as
     * ets:match/2 returns them. Unbound gaps are skipped, which can only
     * happen when a pattern names `$2` without `$1`.
     */
    public List variables(Term object) {
        for (Clause clause : clauses) {
            java.util.Map<java.lang.Integer, Term> bindings = new HashMap<>();
            if (!match(clause.pattern, object, bindings)) {
                continue;
            }
            if (!guards(clause.guards, bindings, object)) {
                continue;
            }
            int highest = highestVariable(clause.pattern);
            java.util.List<Term> values = new ArrayList<>();
            for (int index = 1; index <= highest; index++) {
                Term value = bindings.get(index);
                if (value != null) {
                    values.add(value);
                }
            }
            return listOf(values);
        }
        return null;
    }

    /**
     * Whether any clause matches. Used by match_object/2,3, which has no body
     * and no result to expand.
     */
    public boolean matches(Term object) {
        for (Clause clause : clauses) {
            java.util.Map<java.lang.Integer, Term> bindings = new HashMap<>();
            if (match(clause.pattern, object, bindings)
                && guards(clause.guards, bindings, object)) {
                return true;
            }
        }
        return false;
    }

    // ------------------------------------------------------------------
    // Patterns

    private static boolean match(Term pattern, Term value, java.util.Map<java.lang.Integer, Term> bindings) {
        if (pattern instanceof Atom) {
            if (WILDCARD.equals(pattern)) {
                return true;
            }
            if (WHOLE.equals(pattern)) {
                return bindWhole(value, bindings);
            }
            java.lang.Integer variable = variable(pattern);
            if (variable != null) {
                return bind(variable.intValue(), value, bindings);
            }
            return pattern.equals(value);
        }
        if (pattern instanceof Tuple) {
            if (!(value instanceof Tuple)) {
                return false;
            }
            Tuple patternTuple = (Tuple) pattern;
            Tuple valueTuple = (Tuple) value;
            if (patternTuple.arity() != valueTuple.arity()) {
                return false;
            }
            for (int index = 1; index <= patternTuple.arity(); index++) {
                if (!match(patternTuple.element(index), valueTuple.element(index), bindings)) {
                    return false;
                }
            }
            return true;
        }
        if (pattern instanceof Nil) {
            return isNil(value);
        }
        if (pattern instanceof List) {
            return matchList((List) pattern, value, bindings);
        }
        if (pattern instanceof ImproperList) {
            ImproperList patternList = (ImproperList) pattern;
            if (value instanceof ImproperList) {
                ImproperList valueList = (ImproperList) value;
                return match(patternList.head(), valueList.head(), bindings)
                    && match(patternList.tail(), valueList.tail(), bindings);
            }
            if (isCons(value)) {
                List valueList = (List) value;
                return match(patternList.head(), valueList.head(), bindings)
                    && match(patternList.tail(), valueList.tail(), bindings);
            }
            return false;
        }
        if (pattern instanceof Map) {
            if (!(value instanceof Map)) {
                return false;
            }
            Map patternMap = (Map) pattern;
            Map valueMap = (Map) value;
            List keys = patternMap.keys();
            while (keys.length() > 0) {
                Term key = keys.head();
                Term patternValue = patternMap.get(key);
                Term valueValue = valueMap.get(key);
                if (valueValue == null
                    || !match(patternValue, valueValue, bindings)) {
                    return false;
                }
                keys = keys.tail();
            }
            return true;
        }
        return pattern.equals(value);
    }

    private static boolean matchList(List pattern, Term value, java.util.Map<java.lang.Integer, Term> bindings) {
        List current = pattern;
        Term rest = value;
        while (isCons(current)) {
            if (!isCons(rest)) {
                return false;
            }
            if (!match(((List) current).head(), ((List) rest).head(), bindings)) {
                return false;
            }
            rest = ((List) rest).tail();
            current = ((List) current).tail();
        }
        if (isNil(current)) {
            return isNil(rest);
        }
        // The tail of a list pattern is an improper tail, e.g. [a | Tail].
        return match(current, rest, bindings);
    }

    /**
     * A list cell: a List with a head. Str is a List whose heads are the
     * characters, and List.nil has no head.
     */
    private static boolean isCons(Term term) {
        return term instanceof List && !isNil(term) && ((List) term).head() != null;
    }

    private static boolean isNil(Term term) {
        if (term instanceof Nil) {
            return true;
        }
        return term instanceof List && ((List) term).head() == null;
    }

    private static boolean bind(int variable, Term value, java.util.Map<java.lang.Integer, Term> bindings) {
        Term bound = bindings.get(variable);
        if (bound == null) {
            bindings.put(variable, value);
            return true;
        }
        return bound.equals(value);
    }

    private static boolean bindWhole(Term value, java.util.Map<java.lang.Integer, Term> bindings) {
        return bind(0, value, bindings);
    }

    /**
     * The number of a `'$N'` atom, or null when the atom is not a match spec
     * variable.
     */
    private static java.lang.Integer variable(Term term) {
        if (!(term instanceof Atom)) {
            return null;
        }
        String name = term.toString();
        if (name.length() < 2 || name.charAt(0) != '$' || name.equals("$_")) {
            return null;
        }
        int number = 0;
        for (int index = 1; index < name.length(); index++) {
            char character = name.charAt(index);
            if (character < '0' || character > '9') {
                return null;
            }
            number = number * 10 + (character - '0');
        }
        return number == 0 ? null : java.lang.Integer.valueOf(number);
    }

    private static int highestVariable(Term term) {
        if (term instanceof Atom) {
            java.lang.Integer variable = variable(term);
            return variable == null ? 0 : variable.intValue();
        }
        if (term instanceof Tuple) {
            Tuple tuple = (Tuple) term;
            int highest = 0;
            for (int index = 1; index <= tuple.arity(); index++) {
                highest = Math.max(highest, highestVariable(tuple.element(index)));
            }
            return highest;
        }
        if (term instanceof ImproperList) {
            ImproperList list = (ImproperList) term;
            return Math.max(highestVariable(list.head()), highestVariable(list.tail()));
        }
        if (term instanceof List) {
            List list = (List) term;
            int highest = 0;
            while (isCons(list)) {
                highest = Math.max(highest, highestVariable(list.head()));
                list = list.tail();
            }
            if (!isNil(list)) {
                highest = Math.max(highest, highestVariable(list));
            }
            return highest;
        }
        if (term instanceof Map) {
            Map map = (Map) term;
            List keys = map.keys();
            int highest = 0;
            while (keys.length() > 0) {
                highest = Math.max(highest, highestVariable(keys.head()));
                highest = Math.max(highest, highestVariable(map.get(keys.head())));
                keys = keys.tail();
            }
            return highest;
        }
        return 0;
    }

    // ------------------------------------------------------------------
    // Bodies

    private static Term expand(Term body, java.util.Map<java.lang.Integer, Term> bindings, Term whole) {
        if (body instanceof Atom) {
            if (WHOLE.equals(body) || WHOLE_OBJECT.equals(body)) {
                return whole;
            }
            java.lang.Integer variable = variable(body);
            if (variable != null) {
                Term bound = bindings.get(variable.intValue());
                return bound != null ? bound
                    : bindings.get(0) != null ? bindings.get(0) : List.nil;
            }
            return body;
        }
        if (body instanceof Tuple) {
            Tuple tuple = (Tuple) body;
            Tuple expanded = new Tuple(tuple.arity());
            for (int index = 1; index <= tuple.arity(); index++) {
                expanded.set(index, expand(tuple.element(index), bindings, whole));
            }
            return expanded;
        }
        if (body instanceof ImproperList) {
            ImproperList list = (ImproperList) body;
            return new ImproperList(expand(list.head(), bindings, whole),
                expand(list.tail(), bindings, whole));
        }
        if (body instanceof List) {
            List list = (List) body;
            java.util.List<Term> elements = new ArrayList<>();
            while (isCons(list)) {
                elements.add(expand(list.head(), bindings, whole));
                list = list.tail();
            }
            Term tail = isNil(list) ? List.nil : expand(list, bindings, whole);
            for (int index = elements.size() - 1; index >= 0; index--) {
                if (tail instanceof List) {
                    tail = new List(elements.get(index), (List) tail);
                } else {
                    tail = new ImproperList(elements.get(index), tail);
                }
            }
            return tail;
        }
        if (body instanceof Map) {
            Map map = (Map) body;
            Map expanded = new Map();
            List keys = map.keys();
            while (keys.length() > 0) {
                Term key = keys.head();
                expanded.set(expand(key, bindings, whole),
                    expand(map.get(key), bindings, whole));
                keys = keys.tail();
            }
            return expanded;
        }
        return body;
    }

    // ------------------------------------------------------------------
    // Guards

    private static boolean guards(Term guards, java.util.Map<java.lang.Integer, Term> bindings, Term whole) {
        if (isNil(guards)) {
            return true;
        }
        if (guards instanceof List && ((List) guards).head() != null) {
            List tests = (List) guards;
            while (isCons(tests)) {
                if (!Boolean.am_true.equals(guard(tests.head(), bindings, whole))) {
                    return false;
                }
                tests = tests.tail();
            }
            return true;
        }
        return Boolean.am_true.equals(guard(guards, bindings, whole));
    }

    /**
     * Evaluate one guard test. A guard is a term: variables and literals,
     * truth tests, comparisons, boolean operators, or one of the small BIFs
     * listed in the class comment.
     */
    private static Term guard(Term expression, java.util.Map<java.lang.Integer, Term> bindings, Term whole) {
        if (!(expression instanceof Tuple)) {
            return expand(expression, bindings, whole);
        }
        Tuple call = (Tuple) expression;
        if (call.arity() == 0 || !(call.element(1) instanceof Atom)) {
            return expand(expression, bindings, whole);
        }
        String name = call.element(1).toString();
        if (call.arity() == 3 && "andalso".equals(name)) {
            Term left = guard(call.element(2), bindings, whole);
            if (!Boolean.am_true.equals(left)) {
                return Boolean.am_false;
            }
            return guard(call.element(3), bindings, whole);
        }
        if (call.arity() == 3 && "orelse".equals(name)) {
            Term left = guard(call.element(2), bindings, whole);
            if (Boolean.am_true.equals(left)) {
                return Boolean.am_true;
            }
            return guard(call.element(3), bindings, whole);
        }
        if (call.arity() == 2 && "not".equals(name)) {
            Term value = guard(call.element(2), bindings, whole);
            return Boolean.of(!Boolean.am_true.equals(value));
        }
        if (call.arity() == 3 && comparison(name)) {
            Term left = guard(call.element(2), bindings, whole);
            Term right = guard(call.element(3), bindings, whole);
            return Boolean.of(compare(name, left, right));
        }
        if (call.arity() == 2 && typeTest(name)) {
            return Boolean.of(typeTest(name, guard(call.element(2), bindings, whole)));
        }
        if (call.arity() == 3 && "element".equals(name)) {
            Term index = guard(call.element(2), bindings, whole);
            Term tuple = guard(call.element(3), bindings, whole);
            if (!(index instanceof Integer) || !(tuple instanceof Tuple)) {
                throw new Error(Str.of("badarg in match spec guard"));
            }
            return ((Tuple) tuple).element(((Integer) index).toInt());
        }
        if (call.arity() == 2 && "size".equals(name)) {
            return size(guard(call.element(2), bindings, whole));
        }
        if (call.arity() == 2 && "length".equals(name)) {
            return Integer.of(listLength(guard(call.element(2), bindings, whole)));
        }
        if (call.arity() == 2 && "hd".equals(name)) {
            Term list = guard(call.element(2), bindings, whole);
            if (!isCons(list)) {
                throw new Error(Str.of("badarg in match spec guard"));
            }
            return ((List) list).head();
        }
        if (call.arity() == 2 && "tl".equals(name)) {
            Term list = guard(call.element(2), bindings, whole);
            if (!isCons(list)) {
                throw new Error(Str.of("badarg in match spec guard"));
            }
            return ((List) list).tail();
        }
        if (call.arity() == 2 && "map_size".equals(name)) {
            Term map = guard(call.element(2), bindings, whole);
            if (!(map instanceof Map)) {
                throw new Error(Str.of("badarg in match spec guard"));
            }
            return ((Map) map).size();
        }
        if (call.arity() == 2 && ("byte_size".equals(name) || "bit_size".equals(name))) {
            Term bits = guard(call.element(2), bindings, whole);
            if (!(bits instanceof BitString)) {
                throw new Error(Str.of("badarg in match spec guard"));
            }
            BitString bitString = (BitString) bits;
            int bytes = bitString.bytes().length;
            return Integer.of("byte_size".equals(name) ? bytes : bytes * 8 + bitString.bits());
        }
        throw new Error(Str.of("unsupported match spec guard: " + expression));
    }

    private static boolean comparison(String name) {
        switch (name) {
        case ">":
        case "<":
        case ">=":
        case "=<":
        case "==":
        case "/=":
        case "=:=":
        case "=/=":
            return true;
        default:
            return false;
        }
    }

    private static boolean compare(String operator, Term left, Term right) {
        switch (operator) {
        case "=:=":
            return left.equals(right);
        case "=/=":
            return !left.equals(right);
        case "==":
            return TermOrder.equal(left, right);
        case "/=":
            return !TermOrder.equal(left, right);
        default:
            break;
        }
        int order = TermOrder.compare(left, right);
        switch (operator) {
        case ">":
            return order > 0;
        case "<":
            return order < 0;
        case ">=":
            return order >= 0;
        default:
            return order <= 0;
        }
    }

    private static boolean typeTest(String name) {
        switch (name) {
        case "is_atom":
        case "is_integer":
        case "is_float":
        case "is_number":
        case "is_list":
        case "is_tuple":
        case "is_binary":
        case "is_bitstring":
        case "is_map":
        case "is_pid":
        case "is_reference":
        case "is_function":
        case "is_boolean":
            return true;
        default:
            return false;
        }
    }

    private static boolean typeTest(String name, Term value) {
        switch (name) {
        case "is_atom":
            return value instanceof Atom;
        case "is_integer":
            return value instanceof Integer;
        case "is_float":
            return value instanceof org.jerlang.type.Float;
        case "is_number":
            return value instanceof Number;
        case "is_list":
            return value instanceof List || value instanceof ImproperList;
        case "is_tuple":
            return value instanceof Tuple;
        case "is_binary":
            return value instanceof Binary;
        case "is_bitstring":
            return value instanceof BitString;
        case "is_map":
            return value instanceof Map;
        case "is_pid":
            return value instanceof PID;
        case "is_reference":
            return value instanceof Reference;
        case "is_function":
            return value instanceof Fun;
        default:
            return Boolean.am_true.equals(value) || Boolean.am_false.equals(value);
        }
    }

    private static Term size(Term value) {
        if (value instanceof Tuple) {
            return Integer.of(((Tuple) value).arity());
        }
        if (value instanceof BitString) {
            return Integer.of(((BitString) value).bytes().length);
        }
        throw new Error(Str.of("badarg in match spec guard"));
    }

    private static int listLength(Term value) {
        if (!(value instanceof List) && !(value instanceof ImproperList)) {
            throw new Error(Str.of("badarg in match spec guard"));
        }
        int length = 0;
        Term current = value;
        while (isCons(current)) {
            length++;
            current = ((List) current).tail();
        }
        if (current instanceof ImproperList) {
            length++;
        }
        if (!isNil(current) && !(current instanceof ImproperList)) {
            throw new Error(Str.of("badarg in match spec guard"));
        }
        return length;
    }

    /**
     * The result limit of ets:select/3 and friends: a non-negative integer,
     * or infinity for no limit (-1).
     */
    static int limit(Term limit) {
        if (Atom.of("infinity").equals(limit)) {
            return -1;
        }
        if (!(limit instanceof Integer) || ((Integer) limit).toInt() < 0) {
            throw Error.badarg;
        }
        return ((Integer) limit).toInt();
    }

    static List listOf(java.util.List<Term> terms) {
        List list = List.nil;
        for (int index = terms.size() - 1; index >= 0; index--) {
            list = new List(terms.get(index), list);
        }
        return list;
    }

}
