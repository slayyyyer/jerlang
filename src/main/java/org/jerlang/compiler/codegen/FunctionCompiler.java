package org.jerlang.compiler.codegen;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;

import org.jerlang.Opcode;
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

        List guards = clause.element(4).toList();
        if (guards.length() > 1) {
            throw unsupported("multiple guard alternatives");
        }
        if (guards.length() == 1) {
            List expressions = guards.head().toList();
            while (expressions != List.nil) {
                guard(expressions.head(), fail);
                expressions = expressions.tail();
            }
        }

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
        case "tuple":
            return tuple(tuple.element(3).toList());
        case "match":
            return bind(tuple.element(3), value(tuple.element(4)));
        case "op":
            return arithmetic(tuple);
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

    private Term arithmetic(Tuple tuple) {
        String operator = tuple.element(3).toAtom().toString();
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
        if (!is(target, "atom")) {
            throw unsupported("remote call");
        }
        Atom name = target.toTuple().element(3).toAtom();
        List arguments = tuple.element(4).toList();
        List values = List.nil;
        while (arguments != List.nil) {
            values = new List(value(arguments.head()), values);
            arguments = arguments.tail();
        }
        values = reverse(values);
        int arity = values.length();
        ModuleCompiler.FunctionDef callee = module.function(name, arity);
        for (int index = 0; index < arity; index++) {
            emit(Opcode.move, at(values, index), x(index));
        }
        emit(Opcode.call, Integer.of(arity), f(callee.body));
        Term destination = newY();
        emit(Opcode.move, x(0), destination);
        return destination;
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
