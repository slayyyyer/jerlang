package org.jerlang.compiler.codegen;

import java.math.BigInteger;
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
    private final int handlerCount;
    private int nextY;
    private int nextHandlerY;
    private int nextComprehension;

    FunctionCompiler(ModuleCompiler module, ModuleCompiler.FunctionDef function,
        java.util.List<Instruction> code) {
        this.module = module;
        this.function = function;
        this.code = code;
        int handlers = 2 * countTryNodes(function.clauses);
        this.handlerCount = handlers;
        this.frameSize = function.arity + 8 + countNodes(function.clauses) + handlers;
        this.nextY = function.arity + handlers;
        this.nextHandlerY = function.arity + handlers - 1;
    }

    void compile() {
        emit(Opcode.label, Integer.of(function.entry));
        // The line operand is a one-based index into the Line chunk. OTP puts
        // the line before func_info; beam_disasm and the loader both rely on
        // func_info being followed directly by the function's body label.
        emit(Opcode.line, Integer.of(module.line(module.source(), function.line)));
        emit(Opcode.func_info, atom(module.module()),
            atom(function.name), Integer.of(function.arity));
        emit(Opcode.label, Integer.of(function.body));
        // Calls enter at the body label, so the metadata line before func_info
        // is never executed. Emit the same location again where execution
        // starts; the line table deduplicates it to the same record.
        emit(Opcode.line, Integer.of(module.line(module.source(), function.line)));
        emit(Opcode.allocate, Integer.of(frameSize), Integer.of(function.arity));
        for (int argument = 0; argument < function.arity; argument++) {
            emit(Opcode.move, x(argument), y(argument));
        }
        // Garbage collection treats every word in the allocated frame as a
        // root. Values written by this function are safe, but slots that have
        // not been assigned yet must still contain a valid term; otherwise the
        // first GC scans an arbitrary stack word and can crash the VM.
        List initialized = List.nil;
        for (int register = frameSize - 1; register >= function.arity; register--) {
            initialized = new List(y(register), initialized);
        }
        emit(Opcode.init_yregs, initialized);

        for (int index = 0; index < function.clauses.length(); index++) {
            if (index > 0) {
                emit(Opcode.label, Integer.of(function.clauseLabels.get(index)));
            }
            variables.clear();
            seedFreeVariables(at(function.clauses, index).toTuple());
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
        case "bin":
            bindBinaryPattern(tuple, target, fail);
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
        case "record":
            bindRecordPattern(tuple, target, fail);
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
        case "bin":
            return binaryExpression(tuple);
        case "map":
            return mapExpression(tuple);
        case "record":
            return recordExpression(tuple);
        case "record_field":
            return recordField(tuple);
        case "record_index":
            return recordIndex(tuple);
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
        case "receive":
            return receiveExpression(tuple);
        case "try":
            return tryExpression(tuple);
        case "fun":
            return funExpression(tuple);
        case "named_fun":
            return namedFun(tuple);
        case "lc":
            return value(listComprehension(tuple));
        case "bc":
            return value(binaryComprehension(tuple));
        case "mc":
            return value(mapComprehension(tuple));
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
        return tupleOperands(reverse(operands));
    }

    private Term tupleOperands(List operands) {
        emit(Opcode.test_heap, Integer.of(operands.length() + 1), Integer.of(0));
        Term destination = newY();
        emit(Opcode.put_tuple2, destination, operands);
        return destination;
    }

    private Term recordExpression(Tuple expression) {
        boolean update = expression.arity() == 5;
        Atom recordName = expression.element(update ? 4 : 3).toAtom();
        ModuleCompiler.RecordDef definition = module.recordDefinition(recordName);
        if (definition == null) {
            throw unsupported("unknown record " + recordName);
        }
        List fields = expression.element(update ? 5 : 4).toList();
        Map<Atom, Term> explicit = new HashMap<>();
        while (fields != List.nil) {
            Tuple field = fields.head().toTuple();
            explicit.put(recordFieldName(field.element(3)), field.element(4));
            fields = fields.tail();
        }

        Term base = update ? value(expression.element(3)) : null;
        List operands = List.nil;
        for (int index = 0; index < definition.fields.size(); index++) {
            Term value = explicit.get(definition.fields.get(index));
            if (value != null) {
                operands = new List(value(value), operands);
            } else if (update) {
                Term slot = newY();
                emit(Opcode.get_tuple_element, base, Integer.of(index + 1), slot);
                operands = new List(slot, operands);
            } else {
                operands = new List(value(definition.defaults.get(index)), operands);
            }
        }
        operands = reverse(operands);
        module.atom(recordName);
        operands = new List(atom(recordName), operands);
        return tupleOperands(operands);
    }

    private Term recordField(Tuple expression) {
        Term base = value(expression.element(3));
        Atom recordName = expression.element(4).toAtom();
        Atom field = recordFieldName(expression.element(5));
        int index = module.recordFieldIndex(recordName, field);
        Term destination = newY();
        emit(Opcode.get_tuple_element, base, Integer.of(index), destination);
        return destination;
    }

    private Term recordIndex(Tuple expression) {
        Atom recordName = expression.element(3).toAtom();
        Atom field = recordFieldName(expression.element(4));
        return integer(Integer.of(module.recordFieldIndex(recordName, field)));
    }

    private void bindRecordPattern(Tuple pattern, Term target, int fail) {
        Atom recordName = pattern.element(3).toAtom();
        ModuleCompiler.RecordDef definition = module.recordDefinition(recordName);
        if (definition == null) {
            throw unsupported("unknown record " + recordName);
        }
        List fields = pattern.element(4).toList();
        emit(Opcode.is_tuple, f(fail), target);
        emit(Opcode.test_arity, f(fail), target,
            Integer.of(definition.fields.size() + 1));
        Term tag = newY();
        emit(Opcode.get_tuple_element, target, Integer.of(0), tag);
        module.atom(recordName);
        emit(Opcode.is_eq_exact, f(fail), tag, atom(recordName));
        while (fields != List.nil) {
            Tuple field = fields.head().toTuple();
            Atom fieldName = recordFieldName(field.element(3));
            int index = module.recordFieldIndex(recordName, fieldName);
            Term value = newY();
            emit(Opcode.get_tuple_element, target, Integer.of(index), value);
            bindPattern(field.element(4), value, fail);
            fields = fields.tail();
        }
    }

    private static Atom recordFieldName(Term field) {
        if (!is(field, "atom")) {
            throw unsupported("record field " + tag(field.toTuple()));
        }
        return field.toTuple().element(3).toAtom();
    }

    private Term cons(Term headExpression, Term tailExpression) {
        Term head = value(headExpression);
        Term tail = value(tailExpression);
        emit(Opcode.test_heap, Integer.of(2), Integer.of(0));
        Term destination = newY();
        emit(Opcode.put_list, head, tail, destination);
        return destination;
    }

    private Term string(List characters) {
        Term tail = List.nil;
        characters = reverse(characters);
        while (characters != List.nil) {
            emit(Opcode.test_heap, Integer.of(2), Integer.of(0));
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

    private Term binaryExpression(Tuple expression) {
        List segments = expression.element(3).toList();
        if (segments == List.nil) {
            Term destination = newY();
            emit(Opcode.move, literal(new org.jerlang.type.Binary(new byte[0])), destination);
            return destination;
        }
        java.util.List<Term> operations = new ArrayList<>();
        while (segments != List.nil) {
            Tuple segment = segments.head().toTuple();
            Term valueExpression = segment.element(3);
            Term sizeExpression = segment.element(4);
            BinaryInfo info = binaryInfo(segment.element(5));
            if (is(valueExpression, "string") && !info.binary && !info.utf8) {
                List characters = valueExpression.toTuple().element(3).toList();
                while (characters != List.nil) {
                    int value = characters.head().toInteger().toInt();
                    addBinaryOperation(operations, "integer", 1, 1, List.nil,
                        integerOperand(value), integerOperand(8));
                    characters = characters.tail();
                }
            } else if (info.utf8) {
                addBinaryOperation(operations, "utf8", 0, 8, info.flags,
                    binaryValue(value(valueExpression)), Integer.ZERO);
            } else if (info.binary) {
                Term size = sizeExpression.equals(Atom.of("default"))
                    ? module.atom(Atom.of("all")) : value(sizeExpression);
                addBinaryOperation(operations, info.bits ? "bits" : "binary", 0,
                    info.unit, info.flags, value(valueExpression), binaryValue(size));
            } else {
                Term size = sizeExpression.equals(Atom.of("default"))
                    ? integerOperand(8) : binaryValue(value(sizeExpression));
                addBinaryOperation(operations, "integer", 1, info.unit, info.flags,
                    binaryValue(value(valueExpression)), size);
            }
            segments = segments.tail();
        }

        java.util.Collections.reverse(operations);
        emit(Opcode.bs_create_bin, f(0), Integer.of(0), Integer.of(1), Integer.of(32),
            x(0), Tuple.of(Atom.of("list"), List.of(operations)));
        Term destination = newY();
        emit(Opcode.move, x(0), destination);
        return destination;
    }

    private Term integerOperand(int value) {
        return Tuple.of(Atom.of("integer"), Integer.of(value));
    }

    private Term binaryValue(Term term) {
        return term instanceof Integer ? integerOperand(term.toInteger().toInt()) : term;
    }

    private void addBinaryOperation(java.util.List<Term> operations, String type,
        int segment, int unit, Term flags, Term value, Term size) {
        operations.add(module.atom(Atom.of(type)));
        operations.add(Integer.of(segment));
        operations.add(Integer.of(unit));
        operations.add(flags);
        operations.add(value);
        operations.add(size);
    }

    private void bindBinaryPattern(Tuple pattern, Term target, int fail) {
        List segments = pattern.element(3).toList();
        boolean tailMatch = hasBinaryTail(segments);
        // OTP keeps a tail match context in X1 and extracts into X0 and X2+.
        // get_tail deliberately writes its result over the context in X1.
        // Reusing X0 for the context and X1 for the first value corrupts the
        // first value when get_tail allocates a sub-binary.
        Term context = tailMatch ? x(1) : x(0);
        emit(Opcode.bs_start_match3, f(fail), target, Integer.of(1), context);
        if (tailMatch) {
            // get_tail may allocate a sub-binary. OTP saves the start
            // position in X0 first; that also makes X0 a live, valid root
            // before the command list extracts the first value into it.
            emit(Opcode.bs_get_position, context, x(0), Integer.of(2));
        }
        java.util.List<Term> commands = new ArrayList<>();
        java.util.List<Term> pendingValues = new ArrayList<>();
        java.util.List<Term> pendingPatterns = new ArrayList<>();
        int nextBinaryRegister = tailMatch ? 0 : 1;
        int liveRegisters = 1;
        if (segments == List.nil) {
            commands.add(binaryCommand("ensure_exactly"));
            commands.add(Integer.ZERO);
        } else {
            int fixedBits = 0;
            List scan = segments;
            while (scan != List.nil) {
                Tuple segment = scan.head().toTuple();
                Term valueExpression = segment.element(3);
                Term sizeExpression = segment.element(4);
                BinaryInfo info = binaryInfo(segment.element(5));
                if (is(valueExpression, "string") || info.binary || info.utf8) {
                    break;
                }
                Term size = sizeExpression.equals(Atom.of("default"))
                    ? Integer.of(8) : value(sizeExpression);
                Integer constantSize = constantInteger(size);
                if (constantSize == null) {
                    break;
                }
                fixedBits += constantSize.toInt() * info.unit;
                scan = scan.tail();
            }
            if (fixedBits > 0) {
                commands.add(binaryCommand("ensure_at_least"));
                commands.add(Integer.of(fixedBits));
                commands.add(Integer.of(8));
            }
        }
        while (segments != List.nil) {
            Tuple segment = segments.head().toTuple();
            Term valueExpression = segment.element(3);
            Term sizeExpression = segment.element(4);
            BinaryInfo info = binaryInfo(segment.element(5));
            if (is(valueExpression, "string") && !info.binary && !info.utf8) {
                List characters = valueExpression.toTuple().element(3).toList();
                int bits = characters.length() * 8;
                commands.add(binaryCommand("ensure_at_least"));
                commands.add(Integer.of(bits));
                commands.add(Integer.of(8));
                commands.add(binaryCommand("=:="));
                commands.add(List.nil);
                commands.add(Integer.of(bits));
                BigInteger value = BigInteger.ZERO;
                while (characters != List.nil) {
                    value = value.shiftLeft(8)
                        .or(BigInteger.valueOf(characters.head().toInteger().toLong() & 0xFF));
                    characters = characters.tail();
                }
                commands.add(new Integer(value));
            } else if (info.utf8) {
                throw unsupported("utf8 binary pattern segment");
            } else if (info.binary) {
                boolean tail = sizeExpression.equals(Atom.of("default"));
                Term destination = tail ? x(1) : x(nextBinaryRegister);
                if (!tail) {
                    nextBinaryRegister = nextBinaryRegister == 0 ? 2 : nextBinaryRegister + 1;
                }
                if (tail) {
                    commands.add(binaryCommand("get_tail"));
                    commands.add(Integer.of(Math.max(liveRegisters, 2)));
                    commands.add(Integer.of(info.unit));
                    commands.add(destination);
                } else {
                    commands.add(binaryCommand("binary"));
                    commands.add(Integer.of(liveRegisters));
                    commands.add(info.flags);
                    commands.add(value(sizeExpression));
                    commands.add(Integer.of(info.unit));
                    commands.add(destination);
                }
                pendingValues.add(destination);
                pendingPatterns.add(valueExpression);
                liveRegisters++;
            } else {
                Term size = sizeExpression.equals(Atom.of("default"))
                    ? Integer.of(8) : value(sizeExpression);
                Integer constantSize = constantInteger(size);
                if (isUnused(valueExpression) && constantSize != null) {
                    commands.add(binaryCommand("skip"));
                    commands.add(Integer.of(constantSize.toInt() * info.unit));
                    liveRegisters++;
                } else {
                    int live = tailMatch ? Math.max(liveRegisters, 2) : liveRegisters;
                    Term destination = x(nextBinaryRegister);
                    nextBinaryRegister = nextBinaryRegister == 0 ? 2 : nextBinaryRegister + 1;
                    commands.add(binaryCommand("integer"));
                    commands.add(Integer.of(live));
                    commands.add(info.flags);
                    commands.add(constantSize != null ? constantSize : size);
                    commands.add(Integer.of(info.unit));
                    commands.add(destination);
                    pendingValues.add(destination);
                    pendingPatterns.add(valueExpression);
                    liveRegisters++;
                }
            }
            segments = segments.tail();
        }

        java.util.Collections.reverse(commands);
        emit(Opcode.bs_match, f(fail), context,
            Tuple.of(Atom.of("list"), List.of(commands)));
        for (int index = 0; index < pendingValues.size(); index++) {
            Term destination = newY();
            emit(Opcode.move, pendingValues.get(index), destination);
            bindPattern(pendingPatterns.get(index), destination, fail);
        }
    }

    private boolean hasBinaryTail(List segments) {
        while (segments != List.nil) {
            Tuple segment = segments.head().toTuple();
            BinaryInfo info = binaryInfo(segment.element(5));
            if (info.binary && segment.element(4).equals(Atom.of("default"))) {
                return true;
            }
            segments = segments.tail();
        }
        return false;
    }

    private static Integer constantInteger(Term term) {
        if (term instanceof Integer) {
            return (Integer) term;
        }
        if (is(term, "integer") && term.toTuple().arity() >= 2) {
            Term value = term.toTuple().element(2);
            if (value instanceof Integer) {
                return (Integer) value;
            }
        }
        return null;
    }

    private static boolean isUnused(Term pattern) {
        if (!is(pattern, "var")) {
            return false;
        }
        return pattern.toTuple().element(3).toAtom().toString().startsWith("_");
    }

    private Term literal(Term term) {
        return Tuple.of(Atom.of("literal"), Integer.of(module.literal(term)));
    }

    private Atom binaryCommand(String name) {
        Atom atom = Atom.of(name);
        module.atom(atom);
        return atom;
    }

    private BinaryInfo binaryInfo(Term types) {
        BinaryInfo info = new BinaryInfo();
        if (!(types instanceof List)) {
            return info;
        }
        List names = types.toList();
        java.util.List<Term> flags = new ArrayList<>();
        while (names != List.nil) {
            Term name = names.head();
            if (name instanceof Tuple) {
                Tuple pair = name.toTuple();
                if (pair.arity() >= 2
                    && pair.element(1).toAtom().toString().equals("unit")) {
                    info.unit = pair.element(2).toInteger().toInt();
                }
            } else {
                String value = name.toAtom().toString();
                switch (value) {
                case "binary":
                case "bytes":
                case "bitstring":
                    info.binary = true;
                    info.unit = 8;
                    break;
                case "bits":
                    info.binary = true;
                    info.bits = true;
                    info.unit = 1;
                    break;
                case "utf8":
                    info.utf8 = true;
                    break;
                case "signed":
                case "unsigned":
                case "little":
                case "big":
                case "native":
                case "integer":
                case "float":
                    module.atom(name.toAtom());
                    flags.add(name);
                    break;
                default:
                    break;
                }
            }
            names = names.tail();
        }
        if (!flags.isEmpty()) {
            info.flags = List.of(flags);
        }
        return info;
    }

    private static final class BinaryInfo {
        boolean binary;
        boolean bits;
        boolean utf8;
        int unit = 1;
        Term flags = List.nil;
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

    private Term receiveExpression(Tuple expression) {
        List clauses = expression.element(3).toList();
        if (clauses == List.nil) {
            throw unsupported("receive without clauses");
        }

        int count = clauses.length();
        int loop = label();
        int wait = label();
        int noMatch = label();
        int end = label();
        int[] starts = new int[count];
        for (int index = 0; index < count; index++) {
            starts[index] = label();
        }

        Term result = newY();
        Map<Atom, Term> outside = new HashMap<>(variables);
        Map<Atom, Term> joins = allocateJoins(clauses, outside);
        java.util.List<Map<Atom, Term>> branchMaps = new ArrayList<>();
        boolean hasTimeout = expression.arity() >= 5;
        Term timeout = null;
        List after = List.nil;
        if (hasTimeout) {
            timeout = value(expression.element(4));
            after = expression.element(5).toList();
            if (after == List.nil) {
                throw unsupported("receive after without a body");
            }
        }

        emit(Opcode.label, Integer.of(loop));
        emit(Opcode.loop_rec, f(wait), x(0));

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
                throw unsupported("receive clause with " + patterns.length() + " patterns");
            }
            bindPattern(patterns.head(), x(0), fail);
            compileGuards(clause.element(4).toList(), fail);
            emit(Opcode.remove_message);
            Term branchResult = bodyValue(clause.element(5).toList());
            finishBranch(branchResult, result, end, joins, branchMaps);

            index++;
            cursor = cursor.tail();
        }

        emit(Opcode.label, Integer.of(noMatch));
        emit(Opcode.loop_rec_end, f(loop));
        emit(Opcode.label, Integer.of(wait));
        if (hasTimeout) {
            emit(Opcode.wait_timeout, f(loop), timeout);
            emit(Opcode.timeout);
            variables.clear();
            variables.putAll(outside);
            Term afterResult = bodyValue(after);
            finishBranch(afterResult, result, end, joins, branchMaps);
        } else {
            emit(Opcode.wait, f(loop));
        }

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
        boolean hasCatch = catchClauses != List.nil;
        boolean hasAfter = after != List.nil;
        if (!hasCatch && !hasAfter) {
            throw unsupported("try without a catch or after clause");
        }

        int catchLabel = hasCatch ? label() : 0;
        int end = label();
        int completed = hasAfter ? label() : end;
        int afterException = hasAfter ? label() : 0;
        int rethrow = hasCatch ? label() : 0;
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

        Term result = newY();
        Map<Atom, Term> outside = new HashMap<>(variables);
        Term afterHandler = null;
        if (hasAfter) {
            afterHandler = newHandlerY();
            emit(Opcode._try, afterHandler, f(afterException));
        }

        Term handler = null;
        if (hasCatch) {
            handler = newHandlerY();
            emit(Opcode._try, handler, f(catchLabel));
        }
        Term bodyResult = bodyValue(body);
        if (hasCatch) {
            emit(Opcode.try_end, handler);
        }
        if (ofClauses == List.nil) {
            emit(Opcode.move, bodyResult, result);
            emit(Opcode.jump, f(completed));
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
                emit(Opcode.jump, f(completed));
                ofIndex++;
                ofCursor = ofCursor.tail();
            }
            emit(Opcode.label, Integer.of(ofNoMatch));
            emit(Opcode.try_case_end, bodyResult);
        }

        if (hasCatch) {
            emit(Opcode.label, Integer.of(catchLabel));
            emit(Opcode.try_case, handler);
            Term exceptionClass = newY();
            Term reason = newY();
            Term rawStacktrace = newY();
            emit(Opcode.move, x(0), exceptionClass);
            emit(Opcode.move, x(1), reason);
            emit(Opcode.move, x(2), rawStacktrace);
            // A catch clause that binds Class:Reason:Stack must see a
            // normalized frame list. The raw value stays in Y for a rethrow,
            // because raise/2 expects the unprocessed stacktrace.
            Term stacktrace = newY();
            emit(Opcode.move, rawStacktrace, x(0));
            emit(Opcode.build_stacktrace);
            emit(Opcode.move, x(0), stacktrace);
            Term exception = newY();
            emit(Opcode.test_heap, Integer.of(4), Integer.of(0));
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
                emit(Opcode.jump, f(completed));
                index++;
                cursor = cursor.tail();
            }

            emit(Opcode.label, Integer.of(rethrow));
            emit(Opcode.move, exceptionClass, x(0));
            emit(Opcode.raise, rawStacktrace, reason);
        }

        if (hasAfter) {
            emit(Opcode.label, Integer.of(completed));
            emit(Opcode.try_end, afterHandler);
            compileAfter(after, outside);
            emit(Opcode.jump, f(end));

            emit(Opcode.label, Integer.of(afterException));
            emit(Opcode.try_case, afterHandler);
            Term exceptionClass = newY();
            Term reason = newY();
            Term stacktrace = newY();
            emit(Opcode.move, x(0), exceptionClass);
            emit(Opcode.move, x(1), reason);
            emit(Opcode.move, x(2), stacktrace);
            compileAfter(after, outside);
            emit(Opcode.move, exceptionClass, x(0));
            emit(Opcode.raise, stacktrace, reason);
        }

        emit(Opcode.label, Integer.of(end));
        variables.clear();
        variables.putAll(outside);
        return result;
    }

    private void compileAfter(List after, Map<Atom, Term> outside) {
        variables.clear();
        variables.putAll(outside);
        bodyValue(after);
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
        if (is(pattern, "var")) {
            Atom name = pattern.toTuple().element(3).toAtom();
            if (name.equals(Atom.of("_"))) {
                return value;
            }
            Term destination = newY();
            emit(Opcode.move, value, destination);
            variables.put(name, destination);
            return destination;
        }

        // A match expression accepts the same patterns as a case clause. The
        // value is copied into a Y slot first so a failing pattern can still
        // build the badmatch reason after the source X register is clobbered.
        int fail = label();
        int end = label();
        Term result = newY();
        emit(Opcode.move, value, result);
        bindPattern(pattern, result, fail);
        emit(Opcode.jump, f(end));
        emit(Opcode.label, Integer.of(fail));
        Term reason = newY();
        module.atom(Atom.of("badmatch"));
        emit(Opcode.test_heap, Integer.of(3), Integer.of(0));
        emit(Opcode.put_tuple2, reason,
            List.of(atom(Atom.of("badmatch")), result));
        int errorIndex = module.importIndex(Atom.of("erlang"), Atom.of("error"), 1);
        emit(Opcode.move, reason, x(0));
        emit(Opcode.call_ext, Integer.of(1), Integer.of(errorIndex));
        emit(Opcode.label, Integer.of(end));
        return result;
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
        if (is(target, "var") && function.selfName != null
            && target.toTuple().element(3).toAtom().equals(function.selfName)) {
            List values = argumentValues(arguments);
            if (values.length() != function.patternArity) {
                throw unsupported("named fun self-call arity " + values.length()
                    + " does not match " + function.patternArity);
            }
            for (int index = 0; index < values.length(); index++) {
                emit(Opcode.move, at(values, index), x(index));
            }
            return selfCall(function, values.length(), values);
        }
        if (!is(target, "remote") && !is(target, "atom")) {
            Term fun = value(target);
            return callFun(fun, argumentValues(arguments));
        }

        List values = argumentValues(arguments);
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
        Atom name = target.toTuple().element(3).toAtom();
        if (name.equals(function.selfName) && arity == function.patternArity) {
            return selfCall(function, arity, values);
        }
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

    private Term selfCall(ModuleCompiler.FunctionDef lambda, int arity, List values) {
        List free = lambda.freeNames;
        int position = arity;
        while (free != List.nil) {
            Term value = variables.get(free.head().toAtom());
            if (value == null) {
                throw unsupported("unbound named fun capture " + free.head());
            }
            emit(Opcode.move, value, x(position++));
            free = free.tail();
        }
        emit(Opcode.call, Integer.of(lambda.arity), f(lambda.body));
        Term destination = newY();
        emit(Opcode.move, x(0), destination);
        return destination;
    }

    private List argumentValues(List arguments) {
        List values = List.nil;
        while (arguments != List.nil) {
            values = new List(value(arguments.head()), values);
            arguments = arguments.tail();
        }
        return reverse(values);
    }

    private Term callFun(Term fun, List values) {
        int arity = values.length();
        for (int index = 0; index < arity; index++) {
            emit(Opcode.move, at(values, index), x(index));
        }
        emit(Opcode.move, fun, x(arity));
        emit(Opcode.call_fun, Integer.of(arity));
        Term destination = newY();
        emit(Opcode.move, x(0), destination);
        return destination;
    }

    private Term listComprehension(Tuple expression) {
        int line = expression.element(2).toInteger().toInt();
        return listComprehension(expression.element(3), expression.element(4).toList(),
            nil(line), line);
    }

    private Term binaryComprehension(Tuple expression) {
        int line = expression.element(2).toInteger().toInt();
        // A nested <<X>> is already a complete template expression; only a
        // bare segment such as <<X || ...>> needs bin_element metadata.
        Term first = expression.element(3);
        Term segment = is(first, "bin") ? first
            : Tuple.of(Atom.of("bin"), Integer.of(line),
                List.of(Tuple.of(Atom.of("bin_element"), Integer.of(line), first,
                    Atom.of("default"), Atom.of("default"))));
        Term values = listComprehension(segment, expression.element(4).toList(),
            nil(line), line);
        return call(Atom.of("erlang"), Atom.of("list_to_bitstring"),
            List.of(values), line);
    }

    private Term mapComprehension(Tuple expression) {
        int line = expression.element(2).toInteger().toInt();
        Tuple field = expression.element(3).toTuple();
        Term pair = Tuple.of(Atom.of("tuple"), Integer.of(line),
            List.of(field.element(3), field.element(4)));
        Term values = listComprehension(pair, expression.element(4).toList(),
            nil(line), line);
        return call(Atom.of("maps"), Atom.of("from_list"), List.of(values), line);
    }

    /**
     * Lower a list comprehension to nested {@code lists:foldr/3} calls. Each
     * generator becomes one fold and each filter becomes a case whose
     * non-matching branch returns the accumulator unchanged. Building the
     * loops from foldr instead of a synthetic recursive function keeps the
     * generated module's function table limited to source functions and funs.
     */
    private Term listComprehension(Term template, List qualifiers, Term accumulator, int line) {
        if (qualifiers == List.nil) {
            return cons(template, accumulator, line);
        }

        int id = nextComprehension++;
        Term qualifier = qualifiers.head();
        if (is(qualifier, "generate")) {
            Tuple generator = qualifier.toTuple();
            Term element = variable(Atom.of("__lc_elem_" + id), line);
            Term acc = variable(Atom.of("__lc_acc_" + id), line);
            Term result = listComprehension(template, qualifiers.tail(), acc, line);
            Term matched = caseClause(line, generator.element(3), result);
            Term fallback = caseClause(line, underscore(line), acc);
            Term body = caseExpression(line, element, matched, fallback);
            Term clause = Tuple.of(Atom.of("clause"), Integer.of(line),
                List.of(element, acc), List.nil, List.of(body));
            Term fun = Tuple.of(Atom.of("fun"), Integer.of(line),
                Tuple.of(Atom.of("clauses"), List.of(clause)));
            return call(Atom.of("lists"), Atom.of("foldr"),
                List.of(fun, accumulator, generator.element(4)), line);
        }
        if (is(qualifier, "generate_strict")) {
            throw unsupported("strict list generator");
        }
        if (is(qualifier, "b_generate")) {
            // The foldr lowering walks a list, so a byte-aligned binary is
            // converted with binary_to_list/1 and its single-byte pattern is
            // applied to each element. This is the shape used by the corpus;
            // bit-sized segments are rejected rather than silently misread.
            Tuple generator = qualifier.toTuple();
            Term pattern = generator.element(3);
            Term element = binaryGeneratorPattern(pattern, line);
            Term source = call(Atom.of("erlang"), Atom.of("binary_to_list"),
                List.of(generator.element(4)), line);
            Term generate = Tuple.of(Atom.of("generate"), Integer.of(line), element, source);
            return listComprehension(template, new List(generate, qualifiers.tail()),
                accumulator, line);
        }
        if (is(qualifier, "b_generate_strict")) {
            throw unsupported("strict binary generator");
        }
        if (is(qualifier, "m_generate") || is(qualifier, "m_generate_strict")) {
            throw unsupported("map generator");
        }
        if (is(qualifier, "zip")) {
            throw unsupported("zip generator");
        }

        Term result = listComprehension(template, qualifiers.tail(), accumulator, line);
        Term matched = ifClause(line, List.of(qualifier), result);
        Term fallback = ifClause(line, List.of(astAtom(Atom.of("true"), line)), accumulator);
        return Tuple.of(Atom.of("if"), Integer.of(line), List.of(matched, fallback));
    }

    private Term binaryGeneratorPattern(Term pattern, int line) {
        if (is(pattern, "var")) {
            return pattern;
        }
        if (!is(pattern, "bin")) {
            throw unsupported("binary generator pattern " + tag(pattern.toTuple()));
        }
        List segments = pattern.toTuple().element(3).toList();
        if (segments.length() != 1) {
            throw unsupported("binary generator with " + segments.length() + " segments");
        }
        Tuple segment = segments.head().toTuple();
        Term size = segment.element(4);
        if (!size.equals(Atom.of("default"))
            && (!(size instanceof Tuple) || !size.toTuple().element(3).equals(Integer.of(8)))) {
            throw unsupported("binary generator with a non-byte segment size");
        }
        Term types = segment.element(5);
        if (!types.equals(Atom.of("default"))) {
            throw unsupported("binary generator with segment type " + types);
        }
        return segment.element(3);
    }

    private Term call(Atom moduleName, Atom functionName, List arguments, int line) {
        Term target = Tuple.of(Atom.of("remote"), Integer.of(line),
            astAtom(moduleName, line), astAtom(functionName, line));
        return Tuple.of(Atom.of("call"), Integer.of(line), target, arguments);
    }

    private static Term caseClause(int line, Term pattern, Term body) {
        return Tuple.of(Atom.of("clause"), Integer.of(line), List.of(pattern),
            List.nil, List.of(body));
    }

    private static Term ifClause(int line, List guardExpressions, Term body) {
        return Tuple.of(Atom.of("clause"), Integer.of(line), List.nil,
            List.of(guardExpressions), List.of(body));
    }

    private static Term caseExpression(int line, Term subject, Term... clauses) {
        return Tuple.of(Atom.of("case"), Integer.of(line), subject, List.of(clauses));
    }

    private static Term variable(Atom name, int line) {
        return Tuple.of(Atom.of("var"), Integer.of(line), name);
    }

    private static Term underscore(int line) {
        return variable(Atom.of("_"), line);
    }

    private static Term astAtom(Atom atom, int line) {
        return Tuple.of(Atom.of("atom"), Integer.of(line), atom);
    }

    private static Term nil(int line) {
        return Tuple.of(Atom.of("nil"), Integer.of(line));
    }

    private static Term cons(Term head, Term tail, int line) {
        return Tuple.of(Atom.of("cons"), Integer.of(line), head, tail);
    }

    private Term namedFun(Tuple expression) {
        Atom selfName = expression.element(3).toAtom();
        List clauses = expression.element(4).toList();
        if (clauses == List.nil) {
            throw unsupported("empty named fun");
        }
        int funArity = at(clauses, 0).toTuple().element(3).toList().length();
        List cursor = clauses;
        while (cursor != List.nil) {
            Tuple clause = cursor.head().toTuple();
            if (clause.element(3).toList().length() != funArity) {
                throw unsupported("named fun clauses with different arities");
            }
            cursor = cursor.tail();
        }

        Set<Atom> referenced = new LinkedHashSet<>();
        collectVariables(clauses, referenced);
        referenced.remove(selfName);
        Set<Atom> bound = new LinkedHashSet<>();
        cursor = clauses;
        while (cursor != List.nil) {
            collectVariables(cursor.head().toTuple().element(3), bound);
            cursor = cursor.tail();
        }
        referenced.removeAll(bound);

        List freeNames = List.nil;
        List freeValues = List.nil;
        for (Atom name : referenced) {
            Term value = variables.get(name);
            if (value != null) {
                freeNames = new List(name, freeNames);
                freeValues = new List(value, freeValues);
            }
        }
        freeNames = reverse(freeNames);
        freeValues = reverse(freeValues);

        ModuleCompiler.LambdaDef lambda = module.addLambda(function.name, function.arity,
            clauses, freeNames, funArity);
        lambda.function.selfName = selfName;
        Term destination = newY();
        emit(Opcode.make_fun3, Integer.of(lambda.index), destination,
            Tuple.of(Atom.of("list"), freeValues));
        return destination;
    }

    private Term funExpression(Tuple expression) {
        Term body = expression.element(3);
        if (body instanceof Tuple && is(body.toTuple(), "function")) {
            return funReference(body.toTuple());
        }
        if (!(body instanceof Tuple) || !is(body.toTuple(), "clauses")) {
            throw unsupported("fun reference");
        }
        List clauses = body.toTuple().element(2).toList();
        if (clauses == List.nil) {
            throw unsupported("empty fun");
        }

        int funArity = at(clauses, 0).toTuple().element(3).toList().length();
        List cursor = clauses;
        while (cursor != List.nil) {
            Tuple clause = cursor.head().toTuple();
            if (clause.element(3).toList().length() != funArity) {
                throw unsupported("fun clauses with different arities");
            }
            cursor = cursor.tail();
        }

        Set<Atom> referenced = new LinkedHashSet<>();
        collectVariables(clauses, referenced);
        Set<Atom> bound = new LinkedHashSet<>();
        cursor = clauses;
        while (cursor != List.nil) {
            collectVariables(cursor.head().toTuple().element(3), bound);
            cursor = cursor.tail();
        }
        referenced.removeAll(bound);

        List freeNames = List.nil;
        List freeValues = List.nil;
        for (Atom name : referenced) {
            Term value = variables.get(name);
            if (value != null) {
                freeNames = new List(name, freeNames);
                freeValues = new List(value, freeValues);
            }
        }
        freeNames = reverse(freeNames);
        freeValues = reverse(freeValues);

        ModuleCompiler.LambdaDef lambda = module.addLambda(function.name, function.arity,
            clauses, freeNames, funArity);
        Term destination = newY();
        emit(Opcode.make_fun3, Integer.of(lambda.index), destination,
            Tuple.of(Atom.of("list"), freeValues));
        return destination;
    }

    /**
     * A {function,Name,Arity} (local) or {function,Module,Name,Arity} (remote)
     * fun reference. Both become an external fun through erlang:make_fun/3;
     * the runtime validates that the target exists and is exported.
     */
    private Term funReference(Tuple reference) {
        Atom targetModule = module.module();
        Term nameTerm;
        Term arityTerm;
        if (reference.arity() == 3) {
            nameTerm = reference.element(2);
            arityTerm = reference.element(3);
        } else if (reference.arity() == 4) {
            targetModule = referenceAtom(reference.element(2));
            nameTerm = reference.element(3);
            arityTerm = reference.element(4);
        } else {
            throw unsupported("fun reference with " + (reference.arity() - 1) + " parts");
        }
        Atom targetName = referenceAtom(nameTerm);
        int arity = referenceInteger(arityTerm);
        module.atom(targetModule);
        module.atom(targetName);
        int importIndex = module.importIndex(Atom.of("erlang"), Atom.of("make_fun"), 3);
        emit(Opcode.move, atom(targetModule), x(0));
        emit(Opcode.move, atom(targetName), x(1));
        emit(Opcode.move, integer(Integer.of(arity)), x(2));
        emit(Opcode.call_ext, Integer.of(3), Integer.of(importIndex));
        Term destination = newY();
        emit(Opcode.move, x(0), destination);
        return destination;
    }

    private static Atom referenceAtom(Term term) {
        if (term instanceof Atom) {
            return term.toAtom();
        }
        if (term instanceof Tuple && is(term.toTuple(), "atom")
            && term.toTuple().arity() >= 3) {
            return term.toTuple().element(3).toAtom();
        }
        throw unsupported("fun reference with a non-atom name");
    }

    private static int referenceInteger(Term term) {
        if (term instanceof Integer) {
            return term.toInteger().toInt();
        }
        if (term instanceof Tuple && is(term.toTuple(), "integer")
            && term.toTuple().arity() >= 3) {
            return term.toTuple().element(3).toInteger().toInt();
        }
        throw unsupported("fun reference with a non-integer arity");
    }

    private void seedFreeVariables(Tuple clause) {
        if (function.freeNames == List.nil) {
            return;
        }
        Set<Atom> bound = new LinkedHashSet<>();
        collectVariables(clause.element(3), bound);
        List free = function.freeNames;
        int position = 0;
        while (free != List.nil) {
            Atom name = free.head().toAtom();
            if (!bound.contains(name)) {
                variables.put(name, y(function.patternArity + position));
            }
            position++;
            free = free.tail();
        }
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

    private Term newHandlerY() {
        if (nextHandlerY < function.arity) {
            throw new CodegenException("function " + function + " needs more handler Y registers");
        }
        return y(nextHandlerY--);
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

    private static int countTryNodes(List list) {
        int count = 0;
        while (list != List.nil) {
            count += countTryNodes(list.head());
            list = list.tail();
        }
        return count;
    }

    private static int countTryNodes(Term term) {
        int count = 0;
        if (term instanceof Tuple) {
            Tuple tuple = term.toTuple();
            if (is(tuple, "try")) {
                count++;
            }
            for (int index = 1; index <= tuple.arity(); index++) {
                count += countTryNodes(tuple.element(index));
            }
        } else if (term instanceof List) {
            count += countTryNodes((List) term);
        }
        return count;
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
