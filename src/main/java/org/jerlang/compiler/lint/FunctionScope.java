package org.jerlang.compiler.lint;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.jerlang.type.Atom;
import org.jerlang.type.Term;
import org.jerlang.type.Tuple;

/**
 * = Variable flow for one function
 *
 * This is the part of lint that erlc models with a variable environment, not
 * with a tree walk. A branch may bind a name, all branches may bind it, or a
 * branch may leave the name unsafe for the code after the construct. Keeping
 * the actual binding objects is what lets a later use mark every possible
 * binding as used, so a name bound in every case clause is not reported
 * unused twice just because the compiler stores one object per clause.
 *
 * The abstract format has no columns, so diagnostics stay line-based.
 */
final class FunctionScope {

    private static final class Binding {

        private final String name;
        private final int line;
        private final boolean quiet;
        private boolean used;

        private Binding(String name, int line) {
            this.name = name;
            this.line = line;
            this.quiet = name.startsWith("_");
        }

    }

    private static final class Variable {

        private final String name;
        private final LinkedHashSet<Binding> bindings = new LinkedHashSet<>();
        private boolean safe;
        private String unsafe;
        private int unsafeLine;

        private Variable(String name) {
            this.name = name;
        }

        private Variable copy() {
            Variable copy = new Variable(name);
            copy.safe = safe;
            copy.unsafe = unsafe;
            copy.unsafeLine = unsafeLine;
            copy.bindings.addAll(bindings);
            return copy;
        }

    }

    private static final class Scope {

        private final LinkedHashMap<String, Variable> variables =
            new LinkedHashMap<>();

        private Scope copy() {
            Scope copy = new Scope();
            for (Map.Entry<String, Variable> entry : variables.entrySet()) {
                copy.variables.put(entry.getKey(), entry.getValue().copy());
            }
            return copy;
        }

    }

    private final LintEnvironment environment;
    private final List<Binding> allBindings = new ArrayList<>();
    private final List<String> selfNames = new ArrayList<>();

    FunctionScope(LintEnvironment environment) {
        this.environment = environment;
    }

    void analyse(Term form) {
        if (!(form instanceof Tuple)) {
            return;
        }
        Tuple tuple = (Tuple) form;
        if (!isTag(tuple, "function") || tuple.arity() < 5) {
            return;
        }
        for (Term clause : list(tuple.element(5))) {
            clause(clause, new Scope(), false, null);
        }
        warnUnused();
    }

    // ------------------------------------------------------------- clauses

    private void clause(Term term, Scope scope, boolean shadow, String context) {
        if (!(term instanceof Tuple)) {
            return;
        }
        Tuple clause = (Tuple) term;
        if (!isTag(clause, "clause") || clause.arity() < 4) {
            return;
        }
        Set<String> shadowNames = shadow
            ? new LinkedHashSet<>(scope.variables.keySet())
            : new LinkedHashSet<String>();
        for (Term pattern : list(clause.element(3))) {
            pattern(pattern, scope, shadow, shadowNames, context);
        }
        guards(clause.element(4), scope);
        body(clause.element(5), scope);
    }

    private void body(Term expressions, Scope scope) {
        for (Term expression : list(expressions)) {
            expression(expression, scope);
        }
    }

    // -------------------------------------------------------------- guards

    private void guards(Term alternatives, Scope scope) {
        for (Term alternative : list(alternatives)) {
            for (Term expression : list(alternative)) {
                guard(expression, scope);
            }
        }
    }

    private void guard(Term term, Scope scope) {
        if (!(term instanceof Tuple)) {
            return;
        }
        Tuple tuple = (Tuple) term;
        String tag = tag(tuple);
        switch (tag) {
        case "var":
            use(scope, atomName(tuple.element(3)), lineOf(tuple.element(2)));
            return;
        case "atom":
        case "integer":
        case "float":
        case "char":
        case "string":
        case "nil":
        case "record_index":
            return;
        case "tuple":
            for (Term item : list(tuple.element(3))) {
                guard(item, scope);
            }
            return;
        case "cons":
            guard(tuple.element(3), scope);
            guard(tuple.element(4), scope);
            return;
        case "map":
            guardMap(tuple, scope);
            return;
        case "record":
            guardRecord(tuple, scope);
            return;
        case "record_field":
            if (tuple.arity() == 5) {
                guard(tuple.element(3), scope);
            }
            return;
        case "bin":
            guardBinary(tuple, scope);
            return;
        case "op":
            if (tuple.arity() < 4 || "!".equals(tagOf(tuple.element(3)))) {
                illegalGuard(tuple);
                return;
            }
            guard(tuple.element(4), scope);
            if (tuple.arity() > 4) {
                guard(tuple.element(5), scope);
            }
            return;
        case "call":
            guardCall(tuple, scope);
            return;
        case "remote":
            illegalGuard(tuple);
            return;
        case "match":
        case "case":
        case "if":
        case "receive":
        case "try":
        case "catch":
        case "block":
        case "fun":
        case "named_fun":
        case "lc":
        case "bc":
        case "mc":
        case "maybe":
        case "maybe_match":
            illegalGuard(tuple);
            return;
        default:
            illegalGuard(tuple);
        }
    }

    private void guardMap(Tuple tuple, Scope scope) {
        boolean update = tuple.arity() >= 4;
        if (update) {
            guard(tuple.element(3), scope);
        }
        Term fields = update ? tuple.element(4) : tuple.element(3);
        for (Term item : list(fields)) {
            if (!(item instanceof Tuple)) {
                continue;
            }
            Tuple field = (Tuple) item;
            String tag = tag(field);
            if (!update && "map_field_exact".equals(tag)) {
                environment.error(lineOf(field.element(2)),
                    "only association operators '=>' are allowed in map construction");
            }
            if (field.arity() >= 4) {
                guard(field.element(3), scope);
                guard(field.element(4), scope);
            }
        }
    }

    private void guardRecord(Tuple tuple, Scope scope) {
        if (tuple.arity() >= 5) {
            guard(tuple.element(3), scope);
        }
        Term fields = tuple.arity() >= 5 ? tuple.element(5) : tuple.element(4);
        for (Term item : list(fields)) {
            if (isTag(item, "record_field") && ((Tuple) item).arity() >= 4) {
                guard(((Tuple) item).element(4), scope);
            }
        }
    }

    private void guardBinary(Tuple tuple, Scope scope) {
        for (Term item : list(tuple.element(3))) {
            if (!(item instanceof Tuple)) {
                continue;
            }
            Tuple element = (Tuple) item;
            if (element.arity() >= 5) {
                guard(element.element(4), scope);
                guard(element.element(3), scope);
            }
        }
    }

    private void guardCall(Tuple tuple, Scope scope) {
        if (tuple.arity() < 4) {
            return;
        }
        Term target = tuple.element(3);
        List<Term> args = list(tuple.element(4));
        String local = atomTarget(target);
        if (local != null) {
            String name = local;
            int arity = args.size();
            if (Builtins.guard(name, arity)) {
                obsoleteTypeTestWarning(tuple, name, arity);
                for (Term argument : args) {
                    guard(argument, scope);
                }
            } else if (Builtins.automatic(name, arity)) {
                illegalGuard(tuple);
            } else {
                environment.error(lineOf(tuple.element(2)),
                    "call to local/imported function " + name + "/" + arity
                        + " is illegal in guard");
                for (Term argument : args) {
                    guard(argument, scope);
                }
            }
            return;
        }
        if (isTag(target, "remote")) {
            Tuple remote = (Tuple) target;
            String module = remote.arity() >= 4
                ? atomTarget(remote.element(3)) : null;
            String function = remote.arity() >= 4
                ? atomTarget(remote.element(4)) : null;
            if ("erlang".equals(module) && function != null
                && Builtins.guard(function, args.size())) {
                obsoleteTypeTestWarning(tuple, function, args.size());
                for (Term argument : args) {
                    guard(argument, scope);
                }
            } else {
                illegalGuard(tuple);
            }
            return;
        }
        illegalGuard(tuple);
    }

    private void obsoleteTypeTestWarning(Tuple call, String name, int arity) {
        if (!Builtins.obsoleteTypeTest(name, arity)) {
            return;
        }
        environment.warning(lineOf(call.element(2)),
            name + "/" + arity + " as a type test is obsolete and will be"
                + " removed in OTP 30; use is_" + name + "/" + arity
                + " instead.");
    }

    private void illegalGuard(Tuple tuple) {
        environment.error(lineOf(tuple.element(2)), "illegal guard expression");
    }

    // ---------------------------------------------------------- expressions

    private void expression(Term term, Scope scope) {
        if (!(term instanceof Tuple)) {
            return;
        }
        Tuple tuple = (Tuple) term;
        switch (tag(tuple)) {
        case "var":
            use(scope, atomName(tuple.element(3)), lineOf(tuple.element(2)));
            return;
        case "atom":
        case "integer":
        case "float":
        case "char":
        case "string":
        case "nil":
        case "record_index":
            return;
        case "tuple":
            expressions(tuple.element(3), scope);
            return;
        case "cons":
            expression(tuple.element(3), scope);
            expression(tuple.element(4), scope);
            return;
        case "map":
            map(tuple, scope);
            return;
        case "record":
            record(tuple, scope);
            return;
        case "record_field":
            if (tuple.arity() == 5) {
                expression(tuple.element(3), scope);
            }
            return;
        case "bin":
            binary(tuple, scope);
            return;
        case "call":
            call(tuple, scope);
            return;
        case "op":
            if (tuple.arity() >= 4) {
                expression(tuple.element(4), scope);
            }
            if (tuple.arity() > 4) {
                expression(tuple.element(5), scope);
            }
            return;
        case "match":
            expression(tuple.element(4), scope);
            pattern(tuple.element(3), scope, false,
                new LinkedHashSet<String>(), null);
            return;
        case "case":
            caseExpression(tuple, scope);
            return;
        case "if":
            ifExpression(tuple, scope);
            return;
        case "receive":
            receiveExpression(tuple, scope);
            return;
        case "try":
            tryExpression(tuple, scope);
            return;
        case "catch":
            catchExpression(tuple, scope);
            return;
        case "block":
            body(tuple.element(3), scope);
            return;
        case "fun":
            funExpression(tuple, scope);
            return;
        case "named_fun":
            namedFun(tuple, scope);
            return;
        case "lc":
        case "bc":
            comprehension(tuple, scope);
            return;
        case "mc":
            mapComprehension(tuple, scope);
            return;
        case "maybe":
            maybe(tuple, scope);
            return;
        case "maybe_match":
            expression(tuple.element(4), scope);
            pattern(tuple.element(3), scope, false,
                new LinkedHashSet<String>(), null);
            return;
        case "remote":
            expression(tuple.element(3), scope);
            expression(tuple.element(4), scope);
            return;
        default:
            generic(tuple, scope);
        }
    }

    private void expressions(Term terms, Scope scope) {
        for (Term term : list(terms)) {
            expression(term, scope);
        }
    }

    private void generic(Tuple tuple, Scope scope) {
        for (int index = 2; index <= tuple.arity(); index++) {
            Term element = tuple.element(index);
            if (element instanceof Tuple || isList(element)) {
                genericTerm(element, scope);
            }
        }
    }

    private void genericTerm(Term term, Scope scope) {
        if (term instanceof Tuple) {
            Tuple tuple = (Tuple) term;
            if (tuple.arity() >= 2 && tuple.element(1) instanceof Atom) {
                expression(tuple, scope);
                return;
            }
            for (int index = 1; index <= tuple.arity(); index++) {
                genericTerm(tuple.element(index), scope);
            }
        } else if (isList(term)) {
            for (Term item : list(term)) {
                genericTerm(item, scope);
            }
        }
    }

    // --------------------------------------------------------------- maps

    private void map(Tuple tuple, Scope scope) {
        boolean update = tuple.arity() >= 4;
        if (update) {
            expression(tuple.element(3), scope);
        }
        Term fields = update ? tuple.element(4) : tuple.element(3);
        Set<String> keys = new LinkedHashSet<>();
        boolean invalid = false;
        for (Term item : list(fields)) {
            if (!(item instanceof Tuple)) {
                continue;
            }
            Tuple field = (Tuple) item;
            String tag = tag(field);
            if (!update && "map_field_exact".equals(tag)) {
                environment.error(lineOf(field.element(2)),
                    "only association operators '=>' are allowed in map construction");
                invalid = true;
            }
            expression(field.element(3), scope);
            expression(field.element(4), scope);
            String key = constantKey(field.element(3));
            if (!invalid && "map_field_assoc".equals(tag) && key != null
                && !keys.add(key)) {
                environment.warning(lineOf(field.element(2)),
                    "key " + keyLabel(field.element(3))
                        + " will be overridden in expression");
            }
        }
    }

    private void record(Tuple tuple, Scope scope) {
        boolean update = tuple.arity() >= 5;
        if (update) {
            expression(tuple.element(3), scope);
        }
        Term fields = update ? tuple.element(5) : tuple.element(4);
        for (Term item : list(fields)) {
            if (isTag(item, "record_field") && ((Tuple) item).arity() >= 4) {
                expression(((Tuple) item).element(4), scope);
            }
        }
    }

    private void binary(Tuple tuple, Scope scope) {
        for (Term item : list(tuple.element(3))) {
            if (!(item instanceof Tuple)) {
                continue;
            }
            Tuple element = (Tuple) item;
            if (element.arity() >= 5) {
                if (!isAtom(element.element(4), "default")) {
                    expression(element.element(4), scope);
                }
                expression(element.element(3), scope);
            }
        }
    }

    // --------------------------------------------------------------- calls

    private void call(Tuple call, Scope scope) {
        if (call.arity() < 4) {
            return;
        }
        Term target = call.element(3);
        List<Term> arguments = list(call.element(4));
        String local = atomTarget(target);
        if (local != null) {
            if (!selfNames.contains(local)) {
                environment.localUse(lineOf(call.element(2)), Atom.of(local),
                    arguments.size(), false);
            }
        } else if (isTag(target, "remote")) {
            expression(((Tuple) target).element(3), scope);
            expression(((Tuple) target).element(4), scope);
        } else {
            expression(target, scope);
        }
        for (Term argument : arguments) {
            expression(argument, scope);
        }
    }

    private void funExpression(Tuple fun, Scope scope) {
        if (fun.arity() < 3) {
            return;
        }
        Term body = fun.element(3);
        if (isTag(body, "function")) {
            Tuple reference = (Tuple) body;
            String name = reference.arity() >= 3
                ? atomTarget(reference.element(2)) : null;
            int arity = reference.arity() >= 3
                ? integerValue(reference.element(3)) : -1;
            if (name != null && arity >= 0) {
                environment.localUse(lineOf(fun.element(2)), Atom.of(name),
                    arity, true);
            }
            return;
        }
        if (isTag(body, "clauses") && ((Tuple) body).arity() >= 2) {
            funClauses(list(((Tuple) body).element(2)), scope);
        }
    }

    private void namedFun(Tuple fun, Scope scope) {
        if (fun.arity() < 4 || !(fun.element(3) instanceof Atom)) {
            return;
        }
        String name = fun.element(3).toString();
        Scope selfScope = scope.copy();
        Binding self = new Binding(name, lineOf(fun.element(2)));
        self.used = true;
        allBindings.add(self);
        Variable variable = new Variable(name);
        variable.safe = true;
        variable.bindings.add(self);
        selfScope.variables.put(name, variable);
        selfNames.add(name);
        funClauses(list(fun.element(4)), selfScope);
        selfNames.remove(selfNames.size() - 1);
    }

    private void funClauses(List<Term> clauses, Scope scope) {
        for (Term item : clauses) {
            clause(item, scope.copy(), true, "fun");
        }
    }

    // ------------------------------------------------------- case and friends

    private void caseExpression(Tuple tuple, Scope scope) {
        expression(tuple.element(3), scope);
        List<Scope> branches = new ArrayList<>();
        for (Term item : list(tuple.element(4))) {
            Scope branch = scope.copy();
            clause(item, branch, false, "case");
            branches.add(branch);
        }
        merge(scope, branches, "case", lineOf(tuple.element(2)));
    }

    private void ifExpression(Tuple tuple, Scope scope) {
        List<Scope> branches = new ArrayList<>();
        for (Term item : list(tuple.element(3))) {
            Scope branch = scope.copy();
            clause(item, branch, false, "if");
            branches.add(branch);
        }
        merge(scope, branches, "if", lineOf(tuple.element(2)));
    }

    private void receiveExpression(Tuple tuple, Scope scope) {
        List<Scope> branches = new ArrayList<>();
        if (tuple.arity() >= 5) {
            expression(tuple.element(4), scope);
        }
        for (Term item : list(tuple.element(3))) {
            Scope branch = scope.copy();
            clause(item, branch, false, "receive");
            branches.add(branch);
        }
        if (tuple.arity() >= 5) {
            Scope after = scope.copy();
            body(tuple.element(5), after);
            branches.add(after);
        }
        merge(scope, branches, "receive", lineOf(tuple.element(2)));
    }

    private void tryExpression(Tuple tuple, Scope scope) {
        Scope bodyScope = scope.copy();
        body(tuple.element(3), bodyScope);

        Scope visible = scope.copy();
        addUnsafeFrom(visible, bodyScope, "try", lineOf(tuple.element(2)));

        List<Scope> ofBranches = new ArrayList<>();
        for (Term item : list(tuple.element(4))) {
            Scope branch = visible.copy();
            clause(item, branch, false, "try");
            ofBranches.add(branch);
        }

        List<Scope> catchBranches = new ArrayList<>();
        for (Term item : list(tuple.element(5))) {
            Scope branch = visible.copy();
            clause(item, branch, false, "try");
            catchBranches.add(branch);
        }

        Scope afterScope = visible.copy();
        body(tuple.element(6), afterScope);

        Scope result = scope.copy();
        addUnsafeFrom(result, bodyScope, "try", lineOf(tuple.element(2)));
        for (Scope branch : ofBranches) {
            addUnsafeFrom(result, branch, "try", lineOf(tuple.element(2)));
        }
        for (Scope branch : catchBranches) {
            addUnsafeFrom(result, branch, "try", lineOf(tuple.element(2)));
        }
        addUnsafeFrom(result, afterScope, "try", lineOf(tuple.element(2)));
        replace(scope, result);
    }

    private void catchExpression(Tuple tuple, Scope scope) {
        Scope inner = scope.copy();
        expression(tuple.element(3), inner);
        Scope result = scope.copy();
        addUnsafeFrom(result, inner, "catch", lineOf(tuple.element(2)));
        replace(scope, result);
    }

    private void maybe(Tuple tuple, Scope scope) {
        Scope bodyScope = scope.copy();
        body(tuple.element(3), bodyScope);

        Scope visible = scope.copy();
        addUnsafeFrom(visible, bodyScope, "maybe", lineOf(tuple.element(2)));

        List<Scope> elseBranches = new ArrayList<>();
        int elseLine = 0;
        if (tuple.arity() >= 4 && isTag(tuple.element(4), "else")) {
            Tuple otherwise = (Tuple) tuple.element(4);
            elseLine = lineOf(otherwise.element(2));
            for (Term item : list(otherwise.element(3))) {
                Scope branch = visible.copy();
                clause(item, branch, false, "else");
                elseBranches.add(branch);
            }
        }

        Scope result = scope.copy();
        addUnsafeFrom(result, bodyScope, "maybe", lineOf(tuple.element(2)));
        for (Scope branch : elseBranches) {
            addUnsafeFrom(result, branch, "else", elseLine);
        }
        replace(scope, result);
    }

    private void replace(Scope target, Scope source) {
        target.variables.clear();
        target.variables.putAll(source.variables);
    }

    // ------------------------------------------------------- comprehensions

    private void comprehension(Tuple tuple, Scope scope) {
        Scope comprehension = scope.copy();
        qualifiers(tuple.element(4), comprehension);
        expression(tuple.element(3), comprehension);
    }

    private void mapComprehension(Tuple tuple, Scope scope) {
        Scope comprehension = scope.copy();
        qualifiers(tuple.element(4), comprehension);
        Term field = tuple.element(3);
        if (isTag(field, "map_field_assoc")) {
            expression(((Tuple) field).element(3), comprehension);
            expression(((Tuple) field).element(4), comprehension);
        }
    }

    private void qualifiers(Term terms, Scope scope) {
        for (Term item : list(terms)) {
            if (!(item instanceof Tuple)) {
                continue;
            }
            Tuple qualifier = (Tuple) item;
            String tag = tag(qualifier);
            switch (tag) {
            case "generate":
            case "b_generate":
            case "generate_strict":
            case "b_generate_strict":
                expression(qualifier.element(4), scope);
                bindGenerator(qualifier.element(3), scope);
                break;
            case "m_generate":
            case "m_generate_strict":
                expression(qualifier.element(4), scope);
                mapGenerator(qualifier.element(3), scope);
                break;
            case "zip":
                for (Term inner : list(qualifier.element(3))) {
                    qualifiersSingle(inner, scope);
                }
                break;
            default:
                filter(qualifier, scope);
                break;
            }
        }
    }

    private void qualifiersSingle(Term item, Scope scope) {
        if (!(item instanceof Tuple)) {
            return;
        }
        Tuple qualifier = (Tuple) item;
        String tag = tag(qualifier);
        if ("generate".equals(tag) || "b_generate".equals(tag)
            || "generate_strict".equals(tag) || "b_generate_strict".equals(tag)) {
            expression(qualifier.element(4), scope);
            bindGenerator(qualifier.element(3), scope);
        } else if ("m_generate".equals(tag) || "m_generate_strict".equals(tag)) {
            expression(qualifier.element(4), scope);
            mapGenerator(qualifier.element(3), scope);
        } else {
            filter(qualifier, scope);
        }
    }

    private void filter(Term qualifier, Scope scope) {
        if (isTag(qualifier, "match")) {
            environment.error(lineOf(((Tuple) qualifier).element(2)),
                "matches using '=' are not allowed in comprehension qualifiers");
        }
        // A filter may bind through a case/if expression, and those bindings
        // are visible to the rest of the comprehension. A direct match is
        // illegal, but erlc still keeps its binding while recovering.
        expression(qualifier, scope);
    }

    private void bindGenerator(Term pattern, Scope scope) {
        Set<String> shadows = new LinkedHashSet<>(scope.variables.keySet());
        pattern(pattern, scope, true, shadows, "generate");
    }

    private void mapGenerator(Term field, Scope scope) {
        if (!isTag(field, "map_field_exact") || ((Tuple) field).arity() < 3) {
            return;
        }
        Set<String> shadows = new LinkedHashSet<>(scope.variables.keySet());
        pattern(((Tuple) field).element(3), scope, true, shadows, "generate");
        pattern(((Tuple) field).element(4), scope, true, shadows, "generate");
    }

    // -------------------------------------------------------------- patterns

    private void pattern(Term term, Scope scope, boolean shadow,
        Set<String> shadowNames, String context) {
        if (!(term instanceof Tuple)) {
            return;
        }
        Tuple tuple = (Tuple) term;
        switch (tag(tuple)) {
        case "var":
            bindVariable(scope, atomName(tuple.element(3)),
                lineOf(tuple.element(2)), shadow, shadowNames, context);
            return;
        case "atom":
        case "integer":
        case "float":
        case "char":
        case "string":
        case "nil":
        case "record_index":
            return;
        case "tuple":
            for (Term item : list(tuple.element(3))) {
                pattern(item, scope, shadow, shadowNames, context);
            }
            return;
        case "cons":
            pattern(tuple.element(3), scope, shadow, shadowNames, context);
            pattern(tuple.element(4), scope, shadow, shadowNames, context);
            return;
        case "map":
            mapPattern(tuple, scope, shadow, shadowNames, context);
            return;
        case "record":
            recordPattern(tuple, scope, shadow, shadowNames, context);
            return;
        case "bin":
            binaryPattern(tuple, scope, shadow, shadowNames, context);
            return;
        case "match":
            pattern(tuple.element(3), scope, shadow, shadowNames, context);
            pattern(tuple.element(4), scope, shadow, shadowNames, context);
            return;
        case "op":
            if (numericLiteral(tuple) || constantPattern(tuple)) {
                return;
            }
            if (stringPrefix(tuple)) {
                pattern(tuple.element(5), scope, shadow, shadowNames, context);
                return;
            }
            illegalPattern(tuple);
            return;
        default:
            illegalPattern(tuple);
        }
    }

    private void mapPattern(Tuple tuple, Scope scope, boolean shadow,
        Set<String> shadowNames, String context) {
        boolean impossible = false;
        boolean invalid = false;
        Set<String> keys = new LinkedHashSet<>();
        for (Term item : list(tuple.element(3))) {
            if (!(item instanceof Tuple)) {
                continue;
            }
            Tuple field = (Tuple) item;
            if ("map_field_assoc".equals(tag(field))) {
                environment.error(lineOf(field.element(2)),
                    "illegal pattern, did you mean to use `:=`?");
                invalid = true;
            }
            Term key = field.element(3);
            expression(key, scope);
            String constant = constantKey(key);
            if (constant != null && !keys.add(constant)) {
                impossible = true;
            }
            pattern(field.element(4), scope, shadow, shadowNames, context);
        }
        if (impossible && !invalid) {
            environment.warning(lineOf(tuple.element(2)),
                "pattern cannot possibly match");
        }
    }

    private void recordPattern(Tuple tuple, Scope scope, boolean shadow,
        Set<String> shadowNames, String context) {
        Term fields = tuple.arity() >= 5 ? tuple.element(5) : tuple.element(4);
        for (Term item : list(fields)) {
            if (isTag(item, "record_field") && ((Tuple) item).arity() >= 4) {
                pattern(((Tuple) item).element(4), scope, shadow, shadowNames,
                    context);
            }
        }
    }

    private void binaryPattern(Tuple tuple, Scope scope, boolean shadow,
        Set<String> shadowNames, String context) {
        List<Term> elements = list(tuple.element(3));
        for (int index = 0; index < elements.size(); index++) {
            Term item = elements.get(index);
            if (!(item instanceof Tuple)) {
                continue;
            }
            Tuple element = (Tuple) item;
            if (element.arity() < 5) {
                continue;
            }
            Term value = element.element(3);
            Term size = element.element(4);
            if (index < elements.size() - 1 && isAtom(size, "default")
                && hasNoSizeBinaryType(element.element(5))) {
                environment.error(lineOfForm(value),
                    "a binary field without size is only allowed at the end"
                        + " of a binary pattern");
            }
            if (!isAtom(size, "default")) {
                expression(size, scope);
            }
            pattern(value, scope, shadow, shadowNames, context);
        }
    }

    private void bindVariable(Scope scope, String name, int line, boolean shadow,
        Set<String> shadowNames, String context) {
        if ("_".equals(name)) {
            return;
        }
        Variable existing = scope.variables.get(name);
        if (existing != null && existing.safe
            && (!shadow || !shadowNames.contains(name))) {
            use(scope, name, line);
            return;
        }
        if (existing != null && shadow && !name.startsWith("_")
            && environment.warnShadowVariables()) {
            environment.warning(line, "variable '" + name + "' shadowed in '"
                + context + "'");
        }
        if (shadow) {
            shadowNames.remove(name);
        }
        Binding binding = new Binding(name, line);
        allBindings.add(binding);
        Variable variable = new Variable(name);
        variable.safe = true;
        variable.bindings.add(binding);
        scope.variables.put(name, variable);
    }

    private void use(Scope scope, String name, int line) {
        Variable variable = scope.variables.get(name);
        if (variable == null) {
            environment.error(line, "variable '" + name + "' is unbound");
            return;
        }
        for (Binding binding : variable.bindings) {
            binding.used = true;
        }
        if (!variable.safe) {
            environment.error(line, "variable '" + name + "' unsafe in '"
                + variable.unsafe + "'");
        }
    }

    private void illegalPattern(Tuple tuple) {
        environment.error(lineOf(tuple.element(2)), "illegal pattern");
    }

    // ---------------------------------------------------------------- merge

    private void merge(Scope scope, List<Scope> branches, String context,
        int line) {
        if (branches.isEmpty()) {
            return;
        }
        Set<String> names = new LinkedHashSet<>();
        for (Scope branch : branches) {
            names.addAll(branch.variables.keySet());
        }
        for (String name : names) {
            if (scope.variables.containsKey(name)) {
                continue;
            }
            Variable merged = new Variable(name);
            int seen = 0;
            boolean safe = true;
            String unsafe = null;
            int unsafeLine = 0;
            for (Scope branch : branches) {
                Variable variable = branch.variables.get(name);
                if (variable == null) {
                    safe = false;
                    continue;
                }
                seen++;
                merged.bindings.addAll(variable.bindings);
                if (!variable.safe) {
                    safe = false;
                    if (unsafe == null) {
                        unsafe = variable.unsafe;
                        unsafeLine = variable.unsafeLine;
                    }
                }
            }
            if (seen == 0) {
                continue;
            }
            merged.safe = safe && seen == branches.size();
            if (!merged.safe) {
                merged.unsafe = unsafe == null ? context : unsafe;
                merged.unsafeLine = unsafeLine == 0 ? line : unsafeLine;
            }
            scope.variables.put(name, merged);
        }
    }

    private void addUnsafeFrom(Scope target, Scope source, String context,
        int line) {
        for (Map.Entry<String, Variable> entry : source.variables.entrySet()) {
            Variable existing = target.variables.get(entry.getKey());
            if (existing == null) {
                Variable variable = new Variable(entry.getKey());
                variable.bindings.addAll(entry.getValue().bindings);
                variable.safe = false;
                variable.unsafe = context;
                variable.unsafeLine = line;
                target.variables.put(entry.getKey(), variable);
            } else if (!existing.safe) {
                existing.bindings.addAll(entry.getValue().bindings);
            }
        }
    }

    private void warnUnused() {
        if (!environment.warnUnusedVariables()) {
            return;
        }
        for (Binding binding : allBindings) {
            if (!binding.used && !binding.quiet) {
                environment.warning(binding.line,
                    "variable '" + binding.name + "' is unused");
            }
        }
    }

    // ---------------------------------------------------------------- terms

    private static String atomTarget(Term term) {
        if (term instanceof Atom) {
            return term.toString();
        }
        if (isTag(term, "atom") && ((Tuple) term).arity() >= 3
            && ((Tuple) term).element(3) instanceof Atom) {
            return ((Tuple) term).element(3).toString();
        }
        return null;
    }

    private static int lineOfForm(Term term) {
        if (term instanceof Tuple && ((Tuple) term).arity() >= 2) {
            return lineOf(((Tuple) term).element(2));
        }
        return 0;
    }

    private static int integerValue(Term term) {
        if (term instanceof org.jerlang.type.Integer) {
            return ((org.jerlang.type.Integer) term).toInt();
        }
        if (isTag(term, "integer") && ((Tuple) term).arity() >= 3
            && ((Tuple) term).element(3) instanceof org.jerlang.type.Integer) {
            return ((org.jerlang.type.Integer) ((Tuple) term).element(3)).toInt();
        }
        return -1;
    }

    private static boolean constantPattern(Tuple op) {
        if (op.arity() < 4 || !(op.element(3) instanceof Atom)) {
            return false;
        }
        String operator = op.element(3).toString();
        if (op.arity() == 4) {
            boolean unary = operator.equals("+") || operator.equals("-")
                || operator.equals("bnot") || operator.equals("not");
            return unary && constantTerm(op.element(4));
        }
        if (op.arity() != 5) {
            return false;
        }
        boolean binary = operator.equals("+") || operator.equals("-")
            || operator.equals("*") || operator.equals("/")
            || operator.equals("div") || operator.equals("rem")
            || operator.equals("band") || operator.equals("bor")
            || operator.equals("bxor") || operator.equals("bsl")
            || operator.equals("bsr");
        return binary && constantTerm(op.element(4))
            && constantTerm(op.element(5));
    }

    private static boolean constantTerm(Term term) {
        if (!(term instanceof Tuple)) {
            return false;
        }
        Tuple tuple = (Tuple) term;
        String tag = tag(tuple);
        switch (tag) {
        case "atom":
        case "integer":
        case "float":
        case "char":
        case "string":
        case "nil":
            return true;
        case "tuple":
            for (Term item : list(tuple.element(3))) {
                if (!constantTerm(item)) {
                    return false;
                }
            }
            return true;
        case "cons":
            return constantTerm(tuple.element(3))
                && constantTerm(tuple.element(4));
        case "op":
            return constantPattern(tuple);
        default:
            return false;
        }
    }

    private static boolean stringPrefix(Tuple op) {
        return op.arity() >= 5 && isAtom(op.element(3), "++")
            && constantKey(op.element(4)) != null;
    }

    private static boolean numericLiteral(Tuple op) {
        if (op.arity() < 4 || !(op.element(3) instanceof Atom)) {
            return false;
        }
        String operator = op.element(3).toString();
        if (!operator.equals("+") && !operator.equals("-")) {
            return false;
        }
        Term argument = op.element(4);
        if (!(argument instanceof Tuple)) {
            return false;
        }
        String tag = tag((Tuple) argument);
        return "integer".equals(tag) || "float".equals(tag);
    }

    private static boolean hasNoSizeBinaryType(Term types) {
        if (isAtom(types, "default")) {
            return false;
        }
        for (Term type : list(types)) {
            if (type instanceof Atom) {
                String name = type.toString();
                if (name.equals("binary") || name.equals("bytes")
                    || name.equals("bitstring") || name.equals("bits")) {
                    return true;
                }
            }
        }
        return false;
    }

    private static String constantKey(Term term) {
        if (!(term instanceof Tuple)) {
            return null;
        }
        Tuple tuple = (Tuple) term;
        String tag = tag(tuple);
        switch (tag) {
        case "atom":
            return "a:" + tuple.element(3);
        case "integer":
        case "char":
            return "i:" + tuple.element(3);
        case "float":
            return "f:" + tuple.element(3);
        case "string": {
            List<Term> characters = list(tuple.element(3));
            StringBuilder key = new StringBuilder("l:");
            for (Term character : characters) {
                key.append(character).append('/');
            }
            key.append("[]");
            return key.toString();
        }
        case "nil":
            return "n:";
        case "cons": {
            String head = constantKey(tuple.element(3));
            String tail = constantKey(tuple.element(4));
            return head == null || tail == null ? null : "l:" + head + "|" + tail;
        }
        case "tuple": {
            StringBuilder key = new StringBuilder("t:");
            for (Term item : list(tuple.element(3))) {
                String element = constantKey(item);
                if (element == null) {
                    return null;
                }
                key.append(element).append('/');
            }
            return key.toString();
        }
        case "op":
            if (numericLiteral(tuple)) {
                Term argument = tuple.element(4);
                String sign = tuple.element(3).toString();
                return "i:" + sign + ((Tuple) argument).element(3);
            }
            return null;
        default:
            return null;
        }
    }

    private static String keyLabel(Term term) {
        if (!(term instanceof Tuple)) {
            return term.toString();
        }
        Tuple tuple = (Tuple) term;
        String tag = tag(tuple);
        if ("atom".equals(tag)) {
            return "'" + tuple.element(3) + "'";
        }
        if ("integer".equals(tag) || "char".equals(tag)
            || "float".equals(tag)) {
            return tuple.element(3).toString();
        }
        if ("nil".equals(tag)) {
            return "[]";
        }
        if ("string".equals(tag)) {
            return list(tuple.element(3)).toString();
        }
        if ("tuple".equals(tag)) {
            StringBuilder label = new StringBuilder("{");
            boolean first = true;
            for (Term item : list(tuple.element(3))) {
                if (!first) {
                    label.append(',');
                }
                label.append(keyLabel(item));
                first = false;
            }
            return label.append('}').toString();
        }
        if ("op".equals(tag) && numericLiteral(tuple)) {
            return tuple.element(3).toString() + keyLabel(tuple.element(4));
        }
        return tuple.toString();
    }

    private static List<Term> list(Term term) {
        List<Term> values = new ArrayList<>();
        if (!(term instanceof org.jerlang.type.List)) {
            return values;
        }
        org.jerlang.type.List cursor = term.toList();
        while (cursor != org.jerlang.type.List.nil) {
            values.add(cursor.head());
            org.jerlang.type.List tail = cursor.tail();
            if (!(tail instanceof org.jerlang.type.List)) {
                break;
            }
            cursor = tail;
        }
        return values;
    }

    private static boolean isList(Term term) {
        return term instanceof org.jerlang.type.List;
    }

    private static String tag(Tuple tuple) {
        return tuple.arity() >= 1 ? tagOf(tuple.element(1)) : "";
    }

    private static String tagOf(Term term) {
        return term instanceof Atom ? term.toString() : "";
    }

    private static boolean isTag(Term term, String tag) {
        return term instanceof Tuple && tag.equals(tag((Tuple) term));
    }

    private static boolean isAtom(Term term, String name) {
        return term instanceof Atom && name.equals(term.toString());
    }

    private static String atomName(Term term) {
        return term instanceof Atom ? term.toString() : term.toString();
    }

    private static int lineOf(Term annotation) {
        if (annotation instanceof org.jerlang.type.Integer) {
            return ((org.jerlang.type.Integer) annotation).toInt();
        }
        for (Term item : list(annotation)) {
            if (isTag(item, "location")
                && ((Tuple) item).arity() >= 2
                && ((Tuple) item).element(2) instanceof org.jerlang.type.Integer) {
                return ((org.jerlang.type.Integer) ((Tuple) item).element(2))
                    .toInt();
            }
        }
        return 0;
    }

}
