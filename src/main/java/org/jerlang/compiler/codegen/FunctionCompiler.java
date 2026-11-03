package org.jerlang.compiler.codegen;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

import org.jerlang.Opcode;
import org.jerlang.compiler.lint.Builtins;
import org.jerlang.erts.emulator.Instruction;
import org.jerlang.type.Atom;
import org.jerlang.type.Integer;
import org.jerlang.type.List;
import org.jerlang.type.Term;
import org.jerlang.type.Tuple;

/**
 * Lowers one function. Every value that must survive a call lives in a Y
 * register; X registers are used only for arguments and BIF results. A fresh
 * Y slot is used for each operation result, which is deliberately conservative
 * but makes the call-clobber rule explicit and testable.
 */
final class FunctionCompiler {

    private final ModuleCompiler module;
    private final ModuleCompiler.FunctionDef function;
    private final java.util.List<Instruction> code;
    private final Map<Atom, Term> variables = new HashMap<>();
    private final int frameSize;
    private int nextY;

    FunctionCompiler(ModuleCompiler module, ModuleCompiler.FunctionDef function,
        java.util.List<Instruction> code) {
        this.module = module;
        this.function = function;
        this.code = code;
        this.frameSize = function.arity + 8 + countNodes(function.clauses);
        this.nextY = function.arity;
    }

    void compile() {
        emit(Opcode.label, Integer.of(function.entry));
        emit(Opcode.func_info, atom(module.module()),
            atom(function.name), Integer.of(function.arity));
        emit(Opcode.label, Integer.of(function.body));
        emit(Opcode.allocate, Integer.of(frameSize), Integer.of(function.arity));
        for (int argument = 0; argument < function.arity; argument++) {
            emit(Opcode.move, x(argument), y(argument));
        }

        for (int index = 0; index < function.clauses.length(); index++) {
            if (index > 0) {
                emit(Opcode.label, Integer.of(function.clauseLabels.get(index)));
            }
            variables.clear();
            int fail = index + 1 < function.clauses.length()
                ? function.clauseLabels.get(index + 1) : function.entry;
            compileClause(at(function.clauses, index).toTuple(), fail);
        }
        emit(Opcode.jump, f(function.entry));
    }

    private void compileClause(Tuple clause, int fail) {
        List patterns = clause.element(3).toList();
        for (int index = 0; index < patterns.length(); index++) {
            bindPattern(at(patterns, index), y(index), fail);
        }

        compileGuards(clause.element(4).toList(), fail);

        List body = clause.element(5).toList();
        if (body == List.nil) {
            throw unsupported("empty clause body");
        }
        while (body != List.nil) {
            if (body.tail() == List.nil) {
                emitReturn(body.head());
            } else {
                value(body.head());
            }
            body = body.tail();
        }
    }

    private void compileGuards(List guards, int fail) {
        if (guards == List.nil) {
            return;
        }
        if (guards.length() == 1) {
            guardExpressions(guards.head().toList(), fail);
            return;
        }

        int success = label();
        List alternatives = guards;
        while (alternatives != List.nil) {
            List expressions = alternatives.head().toList();
            boolean last = alternatives.tail() == List.nil;
            int next = last ? fail : label();
            guardExpressions(expressions, next);
            emit(Opcode.jump, f(success));
            if (!last) {
                emit(Opcode.label, Integer.of(next));
            }
            alternatives = alternatives.tail();
        }
        emit(Opcode.label, Integer.of(success));
    }

    private void guardExpressions(List expressions, int fail) {
        while (expressions != List.nil) {
            guard(expressions.head(), fail);
            expressions = expressions.tail();
        }
    }

    private Term bodyValue(List body) {
        if (body == List.nil) {
            throw unsupported("empty clause body");
        }
        Term result = List.nil;
        while (body != List.nil) {
            result = value(body.head());
            body = body.tail();
        }
        return result;
    }

    private void bindPattern(Term pattern, Term target, int fail) {
        Tuple tuple = pattern.toTuple();
        String tag = tag(tuple);
        switch (tag) {
        case "var":
            bindVariable(pattern, target, fail);
            return;
        case "integer":
        case "char":
            emit(Opcode.is_eq_exact, f(fail), target, integerPattern(pattern));
            return;
        case "atom":
            emit(Opcode.is_eq_exact, f(fail), target, atomPattern(pattern));
            return;
        case "nil":
            emit(Opcode.is_nil, f(fail), target);
            return;
        case "cons":
            emit(Opcode.is_nonempty_list, f(fail), target);
            Term head = newY();
            Term tail = newY();
            emit(Opcode.get_list, target, head, tail);
            bindPattern(tuple.element(3), head, fail);
            bindPattern(tuple.element(4), tail, fail);
            return;
        case "string":
            bindString(tuple.element(3).toList(), target, fail);
            return;
        case "map":
            bindMapPattern(tuple, target, fail);
            return;
        case "tuple":
            List elements = tuple.element(3).toList();
            emit(Opcode.is_tuple, f(fail), target);
            emit(Opcode.test_arity, f(fail), target, Integer.of(elements.length()));
            for (int index = 0; index < elements.length(); index++) {
                Term element = newY();
                emit(Opcode.get_tuple_element, target, Integer.of(index), element);
                bindPattern(at(elements, index), element, fail);
            }
            return;
        default:
            throw unsupported("pattern " + tag);
        }
    }

    private void bindVariable(Term pattern, Term target, int fail) {
        Atom name = pattern.toTuple().element(3).toAtom();
        if (name.equals(Atom.of("_"))) {
            return;
        }
        Term existing = variables.get(name);
        if (existing == null) {
            variables.put(name, target);
        } else {
            emit(Opcode.is_eq_exact, f(fail), target, existing);
        }
    }

    private void bindMapPattern(Tuple pattern, Term target, int fail) {
        List fields = pattern.element(3).toList();
        emit(Opcode.is_map, f(fail), target);
        if (fields == List.nil) {
            return;
        }

        java.util.List<Term> operands = new ArrayList<>();
        java.util.List<Term> destinations = new ArrayList<>();
        List cursor = fields;
        while (cursor != List.nil) {
            Tuple field = cursor.head().toTuple();
            operands.add(mapKey(field.element(3)));
            Term destination = newY();
            operands.add(destination);
            destinations.add(destination);
            cursor = cursor.tail();
        }
        java.util.Collections.reverse(operands);
        emit(Opcode.get_map_elements, f(fail), target, List.of(operands));

        cursor = fields;
        int index = 0;
        while (cursor != List.nil) {
            Tuple field = cursor.head().toTuple();
            bindPattern(field.element(4), destinations.get(index++), fail);
            cursor = cursor.tail();
        }
    }

    private Term mapKey(Term key) {
        if (is(key, "atom")) {
            Atom atom = key.toTuple().element(3).toAtom();
            module.atom(atom);
            return Tuple.of(Atom.of("atom"), atom);
        }
        if (is(key, "integer") || is(key, "char")) {
            return integer(key.toTuple().element(3).toInteger());
        }
        throw unsupported("map pattern key " + tag(key.toTuple()));
    }

    private void bindString(List characters, Term target, int fail) {
        if (characters == List.nil) {
            emit(Opcode.is_nil, f(fail), target);
            return;
        }
        emit(Opcode.is_nonempty_list, f(fail), target);
        Term head = newY();
        Term tail = newY();
        emit(Opcode.get_list, target, head, tail);
        emit(Opcode.is_eq_exact, f(fail), head,
            integer(characters.head().toInteger()));
        bindString(characters.tail(), tail, fail);
    }

    private Term integerPattern(Term pattern) {
        return Tuple.of(Atom.of("integer"), pattern.toTuple().element(3).toInteger());
    }

    private Term atomPattern(Term pattern) {
        Atom atom = pattern.toTuple().element(3).toAtom();
        module.atom(atom);
        return Tuple.of(Atom.of("atom"), atom);
    }

    private void guard(Term expression, int fail) {
        Tuple tuple = expression.toTuple();
        String tag = tag(tuple);
        if (tag.equals("atom")) {
            Atom value = tuple.element(3).toAtom();
            if (value.equals(Atom.of("true"))) {
                return;
            }
            if (value.equals(Atom.of("false"))) {
                emit(Opcode.jump, f(fail));
                return;
            }
            throw unsupported("non-boolean guard atom " + value);
        }
        if (tag.equals("op")) {
            guardOperator(tuple, fail);
            return;
        }
        if (tag.equals("call")) {
            guardCall(tuple, fail);
            return;
        }
        throw unsupported("guard " + tag);
    }

    private void guardOperator(Tuple tuple, int fail) {
        String operator = tuple.element(3).toAtom().toString();
        Term left = value(tuple.element(4));
        Term right = value(tuple.element(5));
        switch (operator) {
        case "<":
            emit(Opcode.is_lt, f(fail), left, right);
            return;
        case ">":
            emit(Opcode.is_lt, f(fail), right, left);
            return;
        case "=<":
            emit(Opcode.is_ge, f(fail), right, left);
            return;
        case ">=":
            emit(Opcode.is_ge, f(fail), left, right);
            return;
        case "==":
            emit(Opcode.is_eq, f(fail), left, right);
            return;
        case "/=":
            emit(Opcode.is_ne, f(fail), left, right);
            return;
        case "=:=":
            emit(Opcode.is_eq_exact, f(fail), left, right);
            return;
        case "=/=":
            emit(Opcode.is_ne_exact, f(fail), left, right);
            return;
        default:
            throw unsupported("guard operator " + operator);
        }
    }

    private void guardCall(Tuple tuple, int fail) {
        Term target = tuple.element(3);
        if (!is(target, "atom")) {
            throw unsupported("non-local guard call");
        }
        Atom name = target.toTuple().element(3).toAtom();
        List arguments = tuple.element(4).toList();
        Opcode opcode = typeTest(name);
        if (opcode == null || arguments.length() != 1) {
            throw unsupported("guard call " + name + "/" + arguments.length());
        }
        emit(opcode, f(fail), value(arguments.head()));
    }

    private static Opcode typeTest(Atom name) {
        switch (name.toString()) {
        case "is_atom":
            return Opcode.is_atom;
        case "is_binary":
            return Opcode.is_binary;
        case "is_bitstr":
            return Opcode.is_bitstr;
        case "is_boolean":
            return Opcode.is_boolean;
        case "is_float":
            return Opcode.is_float;
        case "is_function":
            return Opcode.is_function;
        case "is_integer":
            return Opcode.is_integer;
        case "is_list":
            return Opcode.is_list;
        case "is_map":
            return Opcode.is_map;
        case "is_number":
            return Opcode.is_number;
        case "is_pid":
            return Opcode.is_pid;
        case "is_port":
            return Opcode.is_port;
        case "is_reference":
            return Opcode.is_reference;
        case "is_tuple":
            return Opcode.is_tuple;
        default:
            return null;
        }
    }

    private void emitReturn(Term expression) {
        Term result = value(expression);
        if (!result.equals(x(0))) {
            emit(Opcode.move, result, x(0));
        }
        emit(Opcode.deallocate, Integer.of(frameSize));
        emit(Opcode._return);
    }

    private Term value(Term expression) {
        Tuple tuple = expression.toTuple();
        String tag = tag(tuple);
        switch (tag) {
        case "var":
            Atom name = tuple.element(3).toAtom();
            Term register = variables.get(name);
            if (register == null) {
                throw unsupported("unbound variable " + name);
            }
            return register;
        case "integer":
        case "char":
            return Tuple.of(Atom.of("integer"), tuple.element(3).toInteger());
        case "atom":
            Atom atom = tuple.element(3).toAtom();
            module.atom(atom);
            return Tuple.of(Atom.of("atom"), atom);
        case "nil":
            return List.nil;
        case "cons":
            return cons(tuple.element(3), tuple.element(4));
        case "string":
            return string(tuple.element(3).toList());
        case "map":
            return mapExpression(tuple);
        case "tuple":
            return tuple(tuple.element(3).toList());
        case "match":
            return bind(tuple.element(3), value(tuple.element(4)));
        case "op":
            return operator(tuple);
        case "call":
            return call(tuple);
        case "block":
            List expressions = tuple.element(3).toList();
            Term value = List.nil;
            while (expressions != List.nil) {
                value = value(expressions.head());
                expressions = expressions.tail();
            }
            return value;
        case "case":
            return caseExpression(tuple);
        case "if":
            return ifExpression(tuple);
        case "try":
            return tryExpression(tuple);
        default:
            throw unsupported("expression " + tag);
        }
    }

    private Term tuple(List elements) {
        List operands = List.nil;
        while (elements != List.nil) {
            operands = new List(value(elements.head()), operands);
            elements = elements.tail();
        }
        operands = reverse(operands);
        Term destination = newY();
        emit(Opcode.put_tuple2, destination, operands);
        return destination;
    }

    private Term cons(Term headExpression, Term tailExpression) {
        Term head = value(headExpression);
        Term tail = value(tailExpression);
        Term destination = newY();
        emit(Opcode.put_list, head, tail, destination);
        return destination;
    }

    private Term string(List characters) {
        Term tail = List.nil;
        characters = reverse(characters);
        while (characters != List.nil) {
            Term destination = newY();
            emit(Opcode.put_list, integer(characters.head().toInteger()), tail, destination);
            tail = destination;
            characters = characters.tail();
        }
        return tail;
    }

    private Term mapExpression(Tuple expression) {
        Term source;
        List fields;
        if (expression.arity() == 3) {
            source = emptyMap();
            fields = expression.element(3).toList();
        } else {
            source = value(expression.element(3));
            fields = expression.element(4).toList();
        }
        if (fields == List.nil) {
            return source;
        }

        java.util.List<Term> updates = new ArrayList<>();
        int count = 0;
        List cursor = fields;
        while (cursor != List.nil) {
            Tuple field = cursor.head().toTuple();
            updates.add(value(field.element(3)));
            updates.add(value(field.element(4)));
            count++;
            cursor = cursor.tail();
        }
        java.util.Collections.reverse(updates);
        emit(Opcode.put_map_assoc, f(0), source, x(0), Integer.of(count),
            List.of(updates));
        Term destination = newY();
        emit(Opcode.move, x(0), destination);
        return destination;
    }

    private Term emptyMap() {
        int importIndex = module.importIndex(Atom.of("maps"), Atom.of("new"), 0);
        emit(Opcode.call_ext, Integer.of(0), Integer.of(importIndex));
        Term destination = newY();
        emit(Opcode.move, x(0), destination);
        return destination;
    }

    private Term caseExpression(Tuple expression) {
        Term subject = value(expression.element(3));
        List clauses = expression.element(4).toList();
        if (clauses == List.nil) {
            throw unsupported("empty case expression");
        }

        int count = clauses.length();
        int end = label();
        int noMatch = label();
        int[] starts = new int[count];
        for (int index = 0; index < count; index++) {
            starts[index] = label();
        }
        Term result = newY();
        Map<Atom, Term> outside = new HashMap<>(variables);
        Map<Atom, Term> joins = allocateJoins(clauses, outside);
        java.util.List<Map<Atom, Term>> branchMaps = new ArrayList<>();

        int index = 0;
        List cursor = clauses;
        while (cursor != List.nil) {
            Tuple clause = cursor.head().toTuple();
            emit(Opcode.label, Integer.of(starts[index]));
            variables.clear();
            variables.putAll(outside);

            int fail = index + 1 < count ? starts[index + 1] : noMatch;
            List patterns = clause.element(3).toList();
            if (patterns.length() != 1) {
                throw unsupported("case clause with " + patterns.length() + " patterns");
            }
            bindPattern(patterns.head(), subject, fail);
            compileGuards(clause.element(4).toList(), fail);
            Term branchResult = bodyValue(clause.element(5).toList());
            finishBranch(branchResult, result, end, joins, branchMaps);

            index++;
            cursor = cursor.tail();
        }

        emit(Opcode.label, Integer.of(noMatch));
        emit(Opcode.case_end, subject);
        emit(Opcode.label, Integer.of(end));
        restoreBranches(outside, joins, branchMaps);
        return result;
    }

    private Term ifExpression(Tuple expression) {
        List clauses = expression.element(3).toList();
        if (clauses == List.nil) {
            throw unsupported("empty if expression");
        }

        int count = clauses.length();
        int end = label();
        int noMatch = label();
        int[] starts = new int[count];
        for (int index = 0; index < count; index++) {
            starts[index] = label();
        }
        Term result = newY();
        Map<Atom, Term> outside = new HashMap<>(variables);
        Map<Atom, Term> joins = allocateJoins(clauses, outside);
        java.util.List<Map<Atom, Term>> branchMaps = new ArrayList<>();

        int index = 0;
        List cursor = clauses;
        while (cursor != List.nil) {
            Tuple clause = cursor.head().toTuple();
            emit(Opcode.label, Integer.of(starts[index]));
            variables.clear();
            variables.putAll(outside);

            int fail = index + 1 < count ? starts[index + 1] : noMatch;
            compileGuards(clause.element(4).toList(), fail);
            Term branchResult = bodyValue(clause.element(5).toList());
            finishBranch(branchResult, result, end, joins, branchMaps);

            index++;
            cursor = cursor.tail();
        }

        emit(Opcode.label, Integer.of(noMatch));
        emit(Opcode.if_end);
        emit(Opcode.label, Integer.of(end));
        restoreBranches(outside, joins, branchMaps);
        return result;
    }

    private Term tryExpression(Tuple expression) {
        List body = expression.element(3).toList();
        List ofClauses = expression.element(4).toList();
        List catchClauses = expression.element(5).toList();
        List after = expression.element(6).toList();
        if (after != List.nil) {
            throw unsupported("try with an after clause");
        }
        if (catchClauses == List.nil) {
            throw unsupported("try without a catch clause");
        }

        int catchLabel = label();
        int end = label();
        int rethrow = label();
        int count = catchClauses.length();
        int[] starts = new int[count];
        for (int index = 0; index < count; index++) {
            starts[index] = label();
        }
        int ofNoMatch = ofClauses == List.nil ? -1 : label();
        int[] ofStarts = new int[ofClauses.length()];
        for (int index = 0; index < ofStarts.length; index++) {
            ofStarts[index] = label();
        }

        Term handler = newY();
        Term result = newY();
        Map<Atom, Term> outside = new HashMap<>(variables);
        emit(Opcode._try, handler, f(catchLabel));
        Term bodyResult = bodyValue(body);
        emit(Opcode.try_end, handler);
        if (ofClauses == List.nil) {
            emit(Opcode.move, bodyResult, result);
            emit(Opcode.jump, f(end));
        } else {
            Map<Atom, Term> ofOutside = new HashMap<>(variables);
            int ofIndex = 0;
            List ofCursor = ofClauses;
            while (ofCursor != List.nil) {
                Tuple clause = ofCursor.head().toTuple();
                emit(Opcode.label, Integer.of(ofStarts[ofIndex]));
                variables.clear();
                variables.putAll(ofOutside);
                int fail = ofIndex + 1 < ofStarts.length
                    ? ofStarts[ofIndex + 1] : ofNoMatch;
                List patterns = clause.element(3).toList();
                if (patterns.length() != 1) {
                    throw unsupported("try of clause with " + patterns.length() + " patterns");
                }
                bindPattern(patterns.head(), bodyResult, fail);
                compileGuards(clause.element(4).toList(), fail);
                Term branchResult = bodyValue(clause.element(5).toList());
                emit(Opcode.move, branchResult, result);
                emit(Opcode.jump, f(end));
                ofIndex++;
                ofCursor = ofCursor.tail();
            }
            emit(Opcode.label, Integer.of(ofNoMatch));
            emit(Opcode.try_case_end, bodyResult);
        }

        emit(Opcode.label, Integer.of(catchLabel));
        emit(Opcode.try_case, handler);
        Term exceptionClass = newY();
        Term reason = newY();
        Term stacktrace = newY();
        emit(Opcode.move, x(0), exceptionClass);
        emit(Opcode.move, x(1), reason);
        emit(Opcode.move, x(2), stacktrace);
        Term exception = newY();
        emit(Opcode.put_tuple2, exception,
            new List(exceptionClass, new List(reason, new List(stacktrace, List.nil))));

        int index = 0;
        List cursor = catchClauses;
        while (cursor != List.nil) {
            Tuple clause = cursor.head().toTuple();
            emit(Opcode.label, Integer.of(starts[index]));
            variables.clear();
            variables.putAll(outside);
            int fail = index + 1 < count ? starts[index + 1] : rethrow;
            List patterns = clause.element(3).toList();
            if (patterns.length() != 1) {
                throw unsupported("catch clause with " + patterns.length() + " patterns");
            }
            bindPattern(patterns.head(), exception, fail);
            compileGuards(clause.element(4).toList(), fail);
            Term branchResult = bodyValue(clause.element(5).toList());
            emit(Opcode.move, branchResult, result);
            emit(Opcode.jump, f(end));
            index++;
            cursor = cursor.tail();
        }

        emit(Opcode.label, Integer.of(rethrow));
        emit(Opcode.move, exceptionClass, x(0));
        emit(Opcode.raise, stacktrace, reason);
        emit(Opcode.label, Integer.of(end));
        variables.clear();
        variables.putAll(outside);
        return result;
    }

    private Map<Atom, Term> allocateJoins(List clauses, Map<Atom, Term> outside) {
        Set<Atom> names = new LinkedHashSet<>();
        collectVariables(clauses, names);
        Map<Atom, Term> joins = new HashMap<>();
        for (Atom name : names) {
            if (!outside.containsKey(name)) {
                joins.put(name, newY());
            }
        }
        return joins;
    }

    private void finishBranch(Term branchResult, Term result, int end,
        Map<Atom, Term> joins, java.util.List<Map<Atom, Term>> branchMaps) {
        emit(Opcode.move, branchResult, result);
        for (Map.Entry<Atom, Term> join : joins.entrySet()) {
            Term value = variables.get(join.getKey());
            if (value != null) {
                emit(Opcode.move, value, join.getValue());
            }
        }
        branchMaps.add(new HashMap<>(variables));
        emit(Opcode.jump, f(end));
    }

    private void restoreBranches(Map<Atom, Term> outside, Map<Atom, Term> joins,
        java.util.List<Map<Atom, Term>> branchMaps) {
        variables.clear();
        variables.putAll(outside);
        for (Map.Entry<Atom, Term> join : joins.entrySet()) {
            boolean boundEverywhere = true;
            for (Map<Atom, Term> branch : branchMaps) {
                if (!branch.containsKey(join.getKey())) {
                    boundEverywhere = false;
                    break;
                }
            }
            if (boundEverywhere) {
                variables.put(join.getKey(), join.getValue());
            }
        }
    }

    private Term bind(Term pattern, Term value) {
        if (!is(pattern, "var")) {
            throw unsupported("assignment pattern " + tag(pattern.toTuple()));
        }
        Atom name = pattern.toTuple().element(3).toAtom();
        if (name.equals(Atom.of("_"))) {
            return value;
        }
        Term destination = newY();
        emit(Opcode.move, value, destination);
        variables.put(name, destination);
        return destination;
    }

    private Term operator(Tuple tuple) {
        String operator = tuple.element(3).toAtom().toString();
        if (operator.equals("andalso") || operator.equals("orelse")) {
            return logical(tuple, operator.equals("andalso"));
        }
        if (tuple.arity() == 4) {
            return unary(operator, tuple);
        }
        return arithmetic(operator, tuple);
    }

    private Term logical(Tuple tuple, boolean andAlso) {
        Term left = value(tuple.element(4));
        int rightLabel = label();
        int badarg = label();
        int end = label();
        Term result = newY();
        Term shortCircuit = atom(Atom.of(andAlso ? "false" : "true"));
        emit(Opcode.is_boolean, f(badarg), left);
        // `test` jumps on failure: a non-matching value must evaluate the right side.
        emit(Opcode.is_eq_exact, f(rightLabel), left, shortCircuit);
        emit(Opcode.move, shortCircuit, result);
        emit(Opcode.jump, f(end));

        emit(Opcode.label, Integer.of(rightLabel));
        Term right = value(tuple.element(5));
        emit(Opcode.move, right, result);
        emit(Opcode.jump, f(end));

        emit(Opcode.label, Integer.of(badarg));
        int error = module.importIndex(Atom.of("erlang"), Atom.of("error"), 1);
        emit(Opcode.move, atom(Atom.of("badarg")), x(0));
        emit(Opcode.call_ext, Integer.of(1), Integer.of(error));
        emit(Opcode.label, Integer.of(end));
        return result;
    }

    private Term unary(String operator, Tuple tuple) {
        if (!operator.equals("+") && !operator.equals("-")) {
            throw unsupported("unary operator " + operator);
        }
        Term operand = value(tuple.element(4));
        int importIndex = module.importIndex(Atom.of(operator), 2);
        emit(Opcode.gc_bif2, f(0), Integer.of(0), Integer.of(importIndex),
            integer(org.jerlang.type.Integer.ZERO), operand, x(0));
        Term destination = newY();
        emit(Opcode.move, x(0), destination);
        return destination;
    }

    private Term arithmetic(String operator, Tuple tuple) {
        if (!isArithmetic(operator)) {
            throw unsupported("operator " + operator);
        }
        Term left = value(tuple.element(4));
        Term right = value(tuple.element(5));
        int importIndex = module.importIndex(Atom.of(operator), 2);
        emit(Opcode.gc_bif2, f(0), Integer.of(0), Integer.of(importIndex),
            left, right, x(0));
        Term destination = newY();
        emit(Opcode.move, x(0), destination);
        return destination;
    }

    private static boolean isArithmetic(String operator) {
        switch (operator) {
        case "+":
        case "-":
        case "*":
        case "/":
        case "div":
        case "rem":
        case "band":
        case "bor":
        case "bxor":
        case "bsl":
        case "bsr":
            return true;
        default:
            return false;
        }
    }

    private Term call(Tuple tuple) {
        Term target = tuple.element(3);
        List arguments = tuple.element(4).toList();
        List values = List.nil;
        while (arguments != List.nil) {
            values = new List(value(arguments.head()), values);
            arguments = arguments.tail();
        }
        values = reverse(values);
        int arity = values.length();
        for (int index = 0; index < arity; index++) {
            emit(Opcode.move, at(values, index), x(index));
        }

        if (is(target, "remote")) {
            Tuple remote = target.toTuple();
            if (!is(remote.element(3), "atom") || !is(remote.element(4), "atom")) {
                throw unsupported("dynamic remote call");
            }
            Atom moduleName = remote.element(3).toTuple().element(3).toAtom();
            Atom functionName = remote.element(4).toTuple().element(3).toAtom();
            return remoteCall(moduleName, functionName, arity, values);
        }
        if (!is(target, "atom")) {
            throw unsupported("call target " + tag(target.toTuple()));
        }
        Atom name = target.toTuple().element(3).toAtom();
        ModuleCompiler.FunctionDef callee = module.functionOrNull(name, arity);
        if (callee != null) {
            emit(Opcode.call, Integer.of(arity), f(callee.body));
        } else if (Builtins.automatic(name.toString(), arity)) {
            return remoteCall(Atom.of("erlang"), name, arity, values);
        } else {
            throw unsupported("undefined function " + name + "/" + arity);
        }

        Term destination = newY();
        emit(Opcode.move, x(0), destination);
        return destination;
    }

    private Term remoteCall(Atom moduleName, Atom functionName, int arity, List values) {
        int importIndex = module.importIndex(moduleName, functionName, arity);
        emit(Opcode.call_ext, Integer.of(arity), Integer.of(importIndex));
        Term destination = newY();
        emit(Opcode.move, x(0), destination);
        return destination;
    }

    private int label() {
        return module.label();
    }

    private Term newY() {
        if (nextY >= frameSize) {
            throw new CodegenException("function " + function + " needs more Y registers");
        }
        return y(nextY++);
    }

    private void emit(Opcode opcode, Term... arguments) {
        code.add(new Instruction(code.size(), opcode, arguments));
    }

    private static Tuple atom(Atom atom) {
        return Tuple.of(Atom.of("atom"), atom);
    }

    private static Tuple integer(Integer integer) {
        return Tuple.of(Atom.of("integer"), integer);
    }

    private static Tuple x(int index) {
        return Tuple.of(Atom.of("x"), Integer.of(index));
    }

    private static Tuple y(int index) {
        return Tuple.of(Atom.of("y"), Integer.of(index));
    }

    private static Tuple f(int label) {
        return Tuple.of(Atom.of("f"), Integer.of(label));
    }

    private static String tag(Tuple tuple) {
        return tuple.element(1).toAtom().toString();
    }

    private static boolean is(Term term, String name) {
        return term instanceof Tuple && tag(term.toTuple()).equals(name);
    }

    private static Term at(List list, int index) {
        while (index-- > 0) {
            list = list.tail();
        }
        return list.head();
    }

    private static List reverse(List list) {
        List reversed = List.nil;
        while (list != List.nil) {
            reversed = new List(list.head(), reversed);
            list = list.tail();
        }
        return reversed;
    }

    private static void collectVariables(Term term, Set<Atom> names) {
        if (term instanceof Tuple) {
            Tuple tuple = term.toTuple();
            if (is(tuple, "var")) {
                Atom name = tuple.element(3).toAtom();
                if (!name.equals(Atom.of("_"))) {
                    names.add(name);
                }
                return;
            }
            for (int index = 1; index <= tuple.arity(); index++) {
                collectVariables(tuple.element(index), names);
            }
            return;
        }
        if (term instanceof List) {
            List list = (List) term;
            while (list != List.nil) {
                collectVariables(list.head(), names);
                list = list.tail();
            }
        }
    }

    private static int countNodes(List list) {
        int count = 0;
        while (list != List.nil) {
            count += countNodes(list.head());
            list = list.tail();
        }
        return count;
    }

    private static int countNodes(Term term) {
        if (term instanceof Tuple) {
            Tuple tuple = term.toTuple();
            int count = 1;
            for (int index = 1; index <= tuple.arity(); index++) {
                count += countNodes(tuple.element(index));
            }
            return count;
        }
        if (term instanceof List) {
            return 1 + countNodes((List) term);
        }
        return 1;
    }

    private static CodegenException unsupported(String what) {
        return new CodegenException("codegen does not support " + what);
    }

}
