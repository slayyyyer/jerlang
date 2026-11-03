package org.jerlang.compiler.parse;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.jerlang.compiler.scan.Token;
import org.jerlang.type.Atom;
import org.jerlang.type.Float;
import org.jerlang.type.Integer;
import org.jerlang.type.Map;
import org.jerlang.type.Term;
import org.jerlang.type.Tuple;

/**
 * = The Erlang parser
 *
 * The output is the erl_parse abstract format, not a tree of this project's
 * own. That is what makes the parser testable against OTP: epp:parse_file/2
 * returns the same terms, so a disagreement is a diff at a known form and
 * token rather than a judgement about whether two trees mean the same thing.
 *
 * The grammar is a recursive descent transcription of erl_parse.yrl. Lines are
 * taken from the token that erl_parse takes them from, which is not always the
 * token a reader expects: a function clause is annotated with the function
 * name, a case clause with its first pattern, a binary operator with the
 * operator, and a list's first cons with the opening bracket.
 *
 * Types and specifications are parsed by the same class because erl_parse
 * produces one form stream for both; a -spec is an attribute whose value is a
 * type term.
 */
public final class Parser {

    private static final Atom ATOM = Atom.of("atom");
    private static final Atom VAR = Atom.of("var");
    private static final Atom INTEGER = Atom.of("integer");
    private static final Atom FLOAT = Atom.of("float");
    private static final Atom CHAR = Atom.of("char");
    private static final Atom STRING = Atom.of("string");
    private static final Atom DOT = Atom.of("dot");
    private static final Atom EOF = Atom.of("eof");

    private static final Set<String> BUILTIN_TYPES = new HashSet<>(Arrays.asList(
        "any/0", "none/0", "atom/0", "map/0", "integer/0", "float/0",
        "list/1", "nonempty_list/1", "maybe_improper_list/2",
        "nonempty_improper_list/2", "nonempty_maybe_improper_list/2",
        "tuple/0", "pid/0", "port/0", "reference/0", "fun/0", "module/0",
        "node/0", "mfa/0", "arity/0", "identifier/0", "iodata/0", "iolist/0",
        "boolean/0", "byte/0", "char/0", "nil/0", "string/0",
        "nonempty_string/0", "number/0", "timeout/0", "no_return/0",
        "binary/0", "bitstring/0", "dynamic/0"));

    private final List<Token> tokens;
    private int index;
    private boolean allowRemote = true;

    private Parser(List<Token> tokens) {
        this.tokens = tokens;
    }

    /**
     * Parse the forms a preprocessor produced. The trailing {eof, Line} token
     * is part of the stream, exactly as it is part of epp's result.
     */
    public static List<Term> parseForms(List<List<Token>> forms) {
        List<Term> parsed = new ArrayList<>();
        for (List<Token> form : forms) {
            parsed.add(new Parser(form).form());
        }
        return parsed;
    }

    public static Term parseForm(List<Token> form) {
        return new Parser(form).form();
    }

    private Term form() {
        if (isCategory(EOF)) {
            Token token = next();
            return Tuple.of(Atom.of("eof"), Integer.of(token.line()));
        }
        if (isSymbol("-")) {
            return attribute();
        }
        return function();
    }

    // ---------------------------------------------------------------- forms

    private Term function() {
        List<Term> clauses = new ArrayList<>();
        int line = peek().line();
        Atom name = nameOf(peek());
        while (true) {
            clauses.add(functionClause());
            if (isSymbol(";")) {
                next();
                continue;
            }
            break;
        }
        expectDot();
        Tuple first = clauses.get(0).toTuple();
        int arity = first.element(3).toList().length();
        return tuple(atom("function", line), name, Integer.of(arity), list(clauses));
    }

    private Term functionClause() {
        Token name = expectCategory(ATOM);
        List<Term> patterns = argumentPatterns();
        List<Term> guards = guard();
        List<Term> body = body();
        return tuple(atom("clause", name.line()), list(patterns), listOfLists(guards), list(body));
    }

    private List<Term> argumentPatterns() {
        expectSymbol("(");
        List<Term> patterns = new ArrayList<>();
        if (!isSymbol(")")) {
            while (true) {
                patterns.add(pattern(0));
                if (isSymbol(",")) {
                    next();
                    continue;
                }
                break;
            }
        }
        expectSymbol(")");
        return patterns;
    }

    /**
     * A guard is a list of alternatives, each a list of expressions. The
     * semicolons are guard alternatives here because the clause arrow has not
     * been reached yet.
     */
    private List<Term> guard() {
        List<Term> alternatives = new ArrayList<>();
        if (!isSymbol("when")) {
            return alternatives;
        }
        next();
        while (true) {
            List<Term> expressions = new ArrayList<>();
            while (true) {
                expressions.add(expr(0));
                if (isSymbol(",")) {
                    next();
                    continue;
                }
                break;
            }
            alternatives.add(list(expressions));
            if (isSymbol(";")) {
                next();
                continue;
            }
            break;
        }
        return alternatives;
    }

    private List<Term> body() {
        expectSymbol("->");
        List<Term> expressions = new ArrayList<>();
        while (!isClauseEnd()) {
            expressions.add(expr(0));
            if (isSymbol(",")) {
                next();
                continue;
            }
            break;
        }
        return expressions;
    }

    private boolean isClauseEnd() {
        return isSymbol(";") || isCategory(DOT) || isSymbol("end")
            || isSymbol("after") || isSymbol("catch") || isSymbol("else");
    }

    // ------------------------------------------------------------ attributes

    private Term attribute() {
        expectSymbol("-");
        Token nameToken = next();
        String name = nameToken.category().toString();
        if (nameToken.category().equals(ATOM) || nameToken.category().equals(VAR)) {
            name = ((Atom) nameToken.value()).toString();
        }
        int line = nameToken.line();
        switch (name) {
        case "module":
            return moduleAttribute(line);
        case "export":
            return exportAttribute(line, "export");
        case "import":
            return importAttribute(line);
        case "import_record":
            return importRecordAttribute(line);
        case "file":
            return fileAttribute(line);
        case "record":
            return recordAttribute(line);
        case "spec":
        case "callback":
            return specAttribute(line, name);
        case "type":
        case "opaque":
        case "nominal":
            return typeAttribute(line, name);
        default:
            return wildAttribute(line, name);
        }
    }

    private Term moduleAttribute(int line) {
        expectSymbol("(");
        Token module = expectCategory(ATOM);
        Term value = Atom.of(module.value().toString());
        if (isSymbol(",")) {
            next();
            Term parameters = expr(0);
            value = tuple(value, parameters);
        }
        expectSymbol(")");
        expectDot();
        return tuple(atom("attribute", line), Atom.of("module"), value);
    }

    private Term exportAttribute(int line, String tag) {
        expectSymbol("(");
        expectSymbol("[");
        List<Term> exports = new ArrayList<>();
        if (!isSymbol("]")) {
            while (true) {
                Token function = expectCategory(ATOM);
                expectSymbol("/");
                Token arity = expectCategory(INTEGER);
                exports.add(Tuple.of(Atom.of(function.value().toString()), arity.value()));
                if (isSymbol(",")) {
                    next();
                    continue;
                }
                break;
            }
        }
        expectSymbol("]");
        expectSymbol(")");
        expectDot();
        return tuple(atom("attribute", line), Atom.of(tag), list(exports));
    }

    private Term importAttribute(int line) {
        expectSymbol("(");
        Token module = expectCategory(ATOM);
        expectSymbol(",");
        expectSymbol("[");
        List<Term> imports = new ArrayList<>();
        if (!isSymbol("]")) {
            while (true) {
                Token function = expectCategory(ATOM);
                expectSymbol("/");
                Token arity = expectCategory(INTEGER);
                imports.add(Tuple.of(Atom.of(function.value().toString()), arity.value()));
                if (isSymbol(",")) {
                    next();
                    continue;
                }
                break;
            }
        }
        expectSymbol("]");
        expectSymbol(")");
        expectDot();
        return tuple(atom("attribute", line), Atom.of("import"),
            Tuple.of(Atom.of(module.value().toString()), list(imports)));
    }

    private Term importRecordAttribute(int line) {
        expectSymbol("(");
        Token module = expectCategory(ATOM);
        expectSymbol(",");
        Term records = expr(0);
        expectSymbol(")");
        expectDot();
        return tuple(atom("attribute", line), Atom.of("import_record"),
            Tuple.of(Atom.of(module.value().toString()), normalise(records)));
    }

    private Term fileAttribute(int line) {
        expectSymbol("(");
        Token file = expectCategory(STRING);
        expectSymbol(",");
        Token fileLine = expectCategory(INTEGER);
        expectSymbol(")");
        expectDot();
        return tuple(atom("attribute", line), Atom.of("file"),
            tuple(file.value(), fileLine.value()));
    }

    private Term recordAttribute(int line) {
        expectSymbol("(");
        Token name = expectCategory(ATOM);
        expectSymbol(",");
        expectSymbol("{");
        List<Term> fields = new ArrayList<>();
        if (!isSymbol("}")) {
            while (true) {
                fields.add(recordDeclarationField());
                if (isSymbol(",")) {
                    next();
                    continue;
                }
                break;
            }
        }
        expectSymbol("}");
        expectSymbol(")");
        expectDot();
        return tuple(atom("attribute", line), Atom.of("record"),
            Tuple.of(Atom.of(name.value().toString()), list(fields)));
    }

    private Term recordDeclarationField() {
        Token name = expectName();
        int line = name.line();
        Term field = tuple(atom("record_field", line), atom(nameOf(name), line));
        if (isSymbol("=")) {
            next();
            Term value = expr(0);
            field = tuple(atom("record_field", line), atom(nameOf(name), line), value);
        }
        if (isSymbol("::")) {
            next();
            Term type = topType();
            return tuple(atom("typed_record_field", line), field, type);
        }
        return field;
    }

    private Term typeAttribute(int line, String tag) {
        Token name = expectCategory(ATOM);
        List<Term> parameters = new ArrayList<>();
        if (isSymbol("(")) {
            next();
            if (!isSymbol(")")) {
                while (true) {
                    Token parameter = expectCategory(VAR);
                    parameters.add(variable(parameter));
                    if (isSymbol(",")) {
                        next();
                        continue;
                    }
                    break;
                }
            }
            expectSymbol(")");
        }
        expectSymbol("::");
        Term type = topType();
        expectDot();
        return tuple(atom("attribute", line), Atom.of(tag),
            Tuple.of(Atom.of(name.value().toString()), type, list(parameters)));
    }

    private Term specAttribute(int line, String tag) {
        boolean parenthesized = false;
        if (isSymbol("(")) {
            next();
            parenthesized = true;
        }
        Token function = expectCategory(ATOM);
        boolean remote = false;
        String module = null;
        if (isSymbol(":")) {
            next();
            remote = true;
            module = ((Atom) expectCategory(ATOM).value()).toString();
        }
        List<Term> types = new ArrayList<>();
        while (true) {
            Term funType = funType();
            if (isSymbol("when")) {
                next();
                funType = boundedFun(funType);
            }
            types.add(funType);
            if (isSymbol(";")) {
                next();
                continue;
            }
            break;
        }
        if (parenthesized) {
            expectSymbol(")");
        }
        expectDot();
        int arity = specArity(types.get(0));
        Term signature;
        if (remote) {
            signature = Tuple.of(Atom.of(module), Atom.of(function.value().toString()),
                Integer.of(arity));
        } else {
            signature = Tuple.of(Atom.of(function.value().toString()), Integer.of(arity));
        }
        return tuple(atom("attribute", line), Atom.of(tag), tuple(signature, list(types)));
    }

    private Term boundedFun(Term funType) {
        List<Term> constraints = new ArrayList<>();
        while (true) {
            if (isCategory(VAR)) {
                Token variable = next();
                expectSymbol("::");
                Term type = topType();
                constraints.add(tuple(atom("type", variable.line()),
                    atom("constraint", variable.line()),
                    list(tuple(atom("atom", variable.line()),
                        atomLiteral("is_subtype", variable.line())), list(variable(variable), type))));
            } else {
                Token name = expectCategory(ATOM);
                expectSymbol("(");
                List<Term> arguments = new ArrayList<>();
                if (!isSymbol(")")) {
                    while (true) {
                        arguments.add(topType());
                        if (isSymbol(",")) {
                            next();
                            continue;
                        }
                        break;
                    }
                }
                expectSymbol(")");
                if (name.value().toString().equals("is_subtype") && arguments.size() == 1) {
                    constraints.add(tuple(atom("type", name.line()),
                        atom("constraint", name.line()),
                        list(tuple(atom("atom", name.line()),
                            atomLiteral("is_subtype", name.line())), list(arguments))));
                } else {
                    throw error(name.line(), "unsupported type constraint");
                }
            }
            if (isSymbol(",")) {
                next();
                continue;
            }
            break;
        }
        return tuple(atom("type", lineOf(funType)), atom("bounded_fun", lineOf(funType)),
            list(funType, list(constraints)));
    }

    private static int specArity(Term type) {
        if (isTuple(type, "type", "bounded_fun")) {
            type = type.toTuple().element(3).toList().head();
        }
        Tuple tuple = type.toTuple();
        Term args = tuple.element(4).toList().head();
        return args.toTuple().element(3).toList().length();
    }

    private Term wildAttribute(int line, String name) {
        List<Term> values = new ArrayList<>();
        if (isSymbol("(")) {
            next();
            if (!isSymbol(")")) {
                while (true) {
                    values.add(expr(0));
                    if (isSymbol(",")) {
                        next();
                        continue;
                    }
                    break;
                }
            }
            expectSymbol(")");
        } else {
            values.add(atomLiteral("true", line));
        }
        expectDot();
        Term value;
        if (values.size() == 1) {
            value = normalise(attributeFarity(values.get(0)));
        } else {
            value = normalise(tuple(values.toArray(new Term[0])));
        }
        return tuple(atom("attribute", line), Atom.of(name), value);
    }

    private Term attributeFarity(Term term) {
        if (term instanceof Tuple) {
            Tuple tuple = term.toTuple();
            if (tuple.arity() == 5 && isAtomValue(tuple.element(1), "op")
                && isAtomValue(tuple.element(3), "/")) {
                return tuple(Atom.of("tuple"), tuple.element(2),
                    list(tuple.element(4), tuple.element(5)));
            }
            if (isAtomValue(tuple.element(1), "tuple") && tuple.arity() == 3) {
                List<Term> elements = elements(tuple.element(3));
                List<Term> converted = new ArrayList<>();
                for (Term element : elements) {
                    converted.add(attributeFarity(element));
                }
                return tuple(tuple.element(1), tuple.element(2), list(converted));
            }
        }
        if (term instanceof org.jerlang.type.List) {
            List<Term> elements = elements(term);
            List<Term> converted = new ArrayList<>();
            for (Term element : elements) {
                converted.add(attributeFarity(element));
            }
            return list(converted);
        }
        return term;
    }

    // ------------------------------------------------------------ expressions

    private Term expr(int minimum) {
        int line = peek().line();
        if (isSymbol("catch") && minimum <= 0) {
            next();
            return tuple(atom("catch", line), expr(0));
        }
        Term left = prefix();
        while (true) {
            String operator = operator();
            if (operator == null) {
                break;
            }
            int precedence = precedence(operator);
            if (precedence < minimum) {
                break;
            }
            if (operator.equals("(")) {
                next();
                List<Term> arguments = argumentsAfterOpen();
                left = tuple(atom("call", lineOf(left)), left, list(arguments));
                continue;
            }
            if (operator.equals(":") && !allowRemote) {
                break;
            }
            Token operatorToken = next();
            if (operator.equals(":")) {
                Term right = expr(precedence + 1);
                left = tuple(atom("remote", operatorToken.line()), left, right);
                continue;
            }
            if (operator.equals("#")) {
                left = hashSuffix(left, operatorToken.line());
                continue;
            }
            boolean rightAssociative = isRightAssociative(operator);
            Term right = expr(rightAssociative ? precedence : precedence + 1);
            if (operator.equals("=")) {
                left = tuple(atom("match", lineOf(left)), left, right);
            } else {
                left = tuple(atom("op", operatorToken.line()),
                    Atom.of(operator), left, right);
            }
        }
        return left;
    }

    /**
     * Patterns use the expression grammar minus remote calls. A catch clause
     * is the case that needs it: error:R:S has two colons, and the first is
     * the class separator, not a remote call.
     */
    private Term pattern(int minimum) {
        boolean previous = allowRemote;
        allowRemote = false;
        try {
            return expr(minimum);
        } finally {
            allowRemote = previous;
        }
    }

    private Term prefix() {
        if (isSymbol("+") || isSymbol("-") || isSymbol("bnot") || isSymbol("not")) {
            Token operator = next();
            Term argument = expr(600);
            return tuple(atom("op", operator.line()),
                Atom.of(operator.category().toString()), argument);
        }
        return primary();
    }

    private Term primary() {
        Token token = peek();
        String category = token.category().toString();
        switch (category) {
        case "integer":
        case "float":
        case "char":
            next();
            return tuple(Atom.of(category), Integer.of(token.line()), token.value());
        case "atom":
            next();
            return atomLiteral(token.value().toString(), token.line());
        case "var":
            next();
            return variable(token);
        case "string":
            return string();
        case "(":
            next();
            Term inner = expr(0);
            expectSymbol(")");
            return inner;
        case "[":
            return listExpression();
        case "{":
            return tupleExpression();
        case "<<":
            return binary();
        case "#":
            return hashPrimary();
        case "begin":
            return block();
        case "if":
            return ifExpression();
        case "case":
            return caseExpression();
        case "receive":
            return receiveExpression();
        case "try":
            return tryExpression();
        case "fun":
            return funExpression();
        case "maybe":
            return maybeExpression();
        default:
            throw error(token.line(), "unexpected token " + token.category());
        }
    }

    private Term string() {
        Token first = next();
        List<Integer> values = new ArrayList<>();
        appendString(values, first);
        while (isCategory(STRING)) {
            appendString(values, next());
        }
        List<Term> terms = new ArrayList<>();
        for (Integer value : values) {
            terms.add(value);
        }
        return tuple(atom("string", first.line()), list(terms));
    }

    private static void appendString(List<Integer> values, Token token) {
        org.jerlang.type.List list = token.value().toList();
        while (list != org.jerlang.type.List.nil) {
            values.add((Integer) list.head());
            list = list.tail();
        }
    }

    private Term listExpression() {
        Token open = expectSymbol("[");
        if (isSymbol("]")) {
            next();
            return tuple(atom("nil", open.line()));
        }
        List<Term> expressions = new ArrayList<>();
        expressions.add(expr(0));
        if (isSymbol("||")) {
            next();
            List<Term> qualifiers = qualifiers("]");
            expectSymbol("]");
            Term result = expressions.size() == 1 ? expressions.get(0) : list(expressions);
            return tuple(atom("lc", open.line()), result, list(qualifiers));
        }
        while (isSymbol(",")) {
            next();
            expressions.add(expr(0));
        }
        Term tail;
        if (isSymbol("|")) {
            next();
            tail = expr(0);
        } else {
            tail = tuple(atom("nil", peek().line()));
        }
        expectSymbol("]");
        // erl_parse annotates the first cons with the opening bracket and
        // every later cons with the expression that starts it.
        Term result = tail;
        for (int position = expressions.size() - 1; position >= 0; position--) {
            int line = position == 0 ? open.line() : lineOf(expressions.get(position));
            result = tuple(atom("cons", line), expressions.get(position), result);
        }
        return result;
    }

    private Term tupleExpression() {
        Token open = expectSymbol("{");
        if (isSymbol("}")) {
            next();
            return tuple(atom("tuple", open.line()), list(new ArrayList<>()));
        }
        List<Term> elements = new ArrayList<>();
        while (true) {
            elements.add(expr(0));
            if (isSymbol(",")) {
                next();
                continue;
            }
            break;
        }
        expectSymbol("}");
        return tuple(atom("tuple", open.line()), list(elements));
    }

    private Term binary() {
        Token open = expectSymbol("<<");
        if (isSymbol(">>")) {
            next();
            return tuple(atom("bin", open.line()), list(new ArrayList<>()));
        }
        Term first = binaryElementExpression();
        if (isSymbol("||")) {
            next();
            List<Term> qualifiers = qualifiers(">>");
            expectSymbol(">>");
            return tuple(atom("bc", open.line()), first, list(qualifiers));
        }
        List<Term> elements = new ArrayList<>();
        elements.add(binaryElement(first));
        while (isSymbol(",")) {
            next();
            elements.add(binaryElement(binaryElementExpression()));
        }
        expectSymbol(">>");
        return tuple(atom("bin", open.line()), list(elements));
    }

    private Term binaryElementExpression() {
        if (isSymbol("+") || isSymbol("-") || isSymbol("bnot") || isSymbol("not")) {
            Token operator = next();
            Term argument = expr(600);
            return tuple(atom("op", operator.line()),
                Atom.of(operator.category().toString()), argument);
        }
        return primary();
    }

    private Term binaryElement(Term expression) {
        Term size = Atom.of("default");
        Term types = Atom.of("default");
        if (isSymbol(":")) {
            next();
            size = primary();
        }
        if (isSymbol("/")) {
            next();
            List<Term> names = new ArrayList<>();
            while (true) {
                Token type = expectCategory(ATOM);
                Term name = Atom.of(type.value().toString());
                if (isSymbol(":")) {
                    next();
                    Token value = expectCategory(INTEGER);
                    name = Tuple.of(name, value.value());
                }
                names.add(name);
                if (isSymbol("-")) {
                    next();
                    continue;
                }
                break;
            }
            types = list(names);
        }
        return tuple(atom("bin_element", lineOf(expression)), expression, size, types);
    }

    private Term hashPrimary() {
        Token hash = expectSymbol("#");
        return hashBody(hash.line(), null);
    }

    private Term hashSuffix(Term left, int line) {
        return hashBody(line, left);
    }

    private Term hashBody(int line, Term left) {
        if (isSymbol("{")) {
            next();
            List<Term> fields = new ArrayList<>();
            if (!isSymbol("}")) {
                while (true) {
                    fields.add(mapField());
                    if (isSymbol(",")) {
                        next();
                        continue;
                    }
                    break;
                }
            }
            expectSymbol("}");
            if (left == null) {
                return tuple(atom("map", line), list(fields));
            }
            return tuple(atom("map", line), left, list(fields));
        }
        if (isSymbol("_")) {
            throw error(peek().line(), "native records are not supported");
        }
        Token name = expectName();
        String record = nameOf(name).toString();
        if (isSymbol(":")) {
            throw error(peek().line(), "native records are not supported");
        }
        if (isSymbol(".")) {
            next();
            Token field = expectCategory(ATOM);
            if (left == null) {
                return tuple(atom("record_index", line),
                    Atom.of(record), atom(field.value().toString(), field.line()));
            }
            return tuple(atom("record_field", line), left,
                Atom.of(record), atom(field.value().toString(), field.line()));
        }
        expectSymbol("{");
        List<Term> fields = new ArrayList<>();
        if (!isSymbol("}")) {
            while (true) {
                fields.add(recordField());
                if (isSymbol(",")) {
                    next();
                    continue;
                }
                break;
            }
        }
        expectSymbol("}");
        if (left == null) {
            return tuple(atom("record", line), Atom.of(record), list(fields));
        }
        return tuple(atom("record", line), left, Atom.of(record), list(fields));
    }

    private Term recordField() {
        Token name = expectName();
        expectSymbol("=");
        Term value = expr(0);
        return tuple(atom("record_field", name.line()), atom(nameOf(name), name.line()), value);
    }

    private Term mapField() {
        Term key = expr(0);
        if (isSymbol("=>")) {
            Token operator = next();
            return tuple(atom("map_field_assoc", operator.line()), key, expr(0));
        }
        if (isSymbol(":=")) {
            Token operator = next();
            return tuple(atom("map_field_exact", operator.line()), key, expr(0));
        }
        throw error(peek().line(), "expected => or :=");
    }

    private Term block() {
        Token begin = expectSymbol("begin");
        List<Term> expressions = expressionsUntil("end");
        expectSymbol("end");
        return tuple(atom("block", begin.line()), list(expressions));
    }

    private Term ifExpression() {
        Token ifToken = expectSymbol("if");
        List<Term> clauses = new ArrayList<>();
        while (true) {
            List<Term> guards = new ArrayList<>();
            while (true) {
                List<Term> expressions = new ArrayList<>();
                while (true) {
                    expressions.add(expr(0));
                    if (isSymbol(",")) {
                        next();
                        continue;
                    }
                    break;
                }
                guards.add(list(expressions));
                if (isSymbol(";")) {
                    next();
                    continue;
                }
                break;
            }
            expectSymbol("->");
            List<Term> body = expressionsUntil(";", "end");
            int line = lineOf(elements(guards.get(0)).get(0));
            clauses.add(tuple(atom("clause", line), list(new ArrayList<>()),
                list(guards), list(body)));
            if (isSymbol(";")) {
                next();
                continue;
            }
            break;
        }
        expectSymbol("end");
        return tuple(atom("if", ifToken.line()), list(clauses));
    }

    private Term caseExpression() {
        Token caseToken = expectSymbol("case");
        Term expression = expr(0);
        expectSymbol("of");
        List<Term> clauses = caseClauses("end");
        expectSymbol("end");
        return tuple(atom("case", caseToken.line()), expression, list(clauses));
    }

    private Term receiveExpression() {
        Token receive = expectSymbol("receive");
        List<Term> clauses = new ArrayList<>();
        if (!isSymbol("after")) {
            clauses = caseClauses("after", "end");
        }
        if (isSymbol("after")) {
            next();
            Term timeout = expr(0);
            expectSymbol("->");
            List<Term> body = expressionsUntil("end");
            expectSymbol("end");
            return tuple(atom("receive", receive.line()), list(clauses),
                timeout, list(body));
        }
        expectSymbol("end");
        return tuple(atom("receive", receive.line()), list(clauses));
    }

    private List<Term> caseClauses(String... stops) {
        List<Term> clauses = new ArrayList<>();
        while (true) {
            Term pattern = pattern(0);
            List<Term> guards = guard();
            expectSymbol("->");
            List<Term> body = expressionsUntil(stops);
            clauses.add(tuple(atom("clause", lineOf(pattern)), list(pattern),
                listOfLists(guards), list(body)));
            if (isSymbol(";")) {
                next();
                continue;
            }
            break;
        }
        return clauses;
    }

    private Term tryExpression() {
        Token tryToken = expectSymbol("try");
        List<Term> body = expressionsUntil("of", "catch", "after", "end");
        List<Term> ofClauses = new ArrayList<>();
        if (isSymbol("of")) {
            next();
            ofClauses = caseClauses("catch", "after", "end");
        }
        List<Term> catchClauses = new ArrayList<>();
        if (isSymbol("catch")) {
            next();
            catchClauses = tryClauses();
        }
        List<Term> after = new ArrayList<>();
        if (isSymbol("after")) {
            next();
            after = expressionsUntil("end");
        }
        expectSymbol("end");
        return tuple(atom("try", tryToken.line()), list(body), list(ofClauses),
            list(catchClauses), list(after));
    }

    private List<Term> tryClauses() {
        List<Term> clauses = new ArrayList<>();
        while (true) {
            int line = peek().line();
            if ((isCategory(ATOM) || isCategory(VAR)) && lookahead(1).category().equals(Atom.of(":"))) {
                Token classToken = next();
                expectSymbol(":");
                Term pattern = pattern(0);
                Term stack = variable("_", previous().line());
                if (isSymbol(":")) {
                    next();
                    stack = expr(0);
                }
                int clauseLine = classToken.line();
                Term classTerm = classToken.category().equals(ATOM)
                    ? atom(classToken.value().toString(), classToken.line())
                    : variable(classToken);
                List<Term> guards = guard();
                expectSymbol("->");
                List<Term> body = expressionsUntil(";", "end", "after");
                clauses.add(tuple(atom("clause", clauseLine),
                    list(tuple(atom("tuple", clauseLine), list(classTerm, pattern, stack))),
                    listOfLists(guards), list(body)));
            } else {
                Term pattern = pattern(0);
                int patternLine = lineOf(pattern);
                Term stack = variable("_", line);
                List<Term> guards = guard();
                expectSymbol("->");
                List<Term> body = expressionsUntil(";", "end", "after");
                clauses.add(tuple(atom("clause", patternLine),
                    list(tuple(atom("tuple", patternLine), list(atom("throw", patternLine), pattern, stack))),
                    listOfLists(guards), list(body)));
            }
            if (isSymbol(";")) {
                next();
                continue;
            }
            break;
        }
        return clauses;
    }

    private Term funExpression() {
        Token fun = expectSymbol("fun");
        if (isCategory(ATOM) || isCategory(VAR)) {
            if (lookahead(1).category().equals(Atom.of("/"))) {
                Token name = next();
                expectSymbol("/");
                Token arity = expectCategory(INTEGER);
                return tuple(atom("fun", fun.line()),
                    Tuple.of(Atom.of("function"), Atom.of(name.value().toString()),
                        arity.value()));
            }
            if (lookahead(1).category().equals(Atom.of(":"))) {
                Token module = next();
                expectSymbol(":");
                Token name = next();
                expectSymbol("/");
                Token arity = next();
                return tuple(atom("fun", fun.line()),
                    Tuple.of(Atom.of("function"), nameTerm(module), nameTerm(name),
                        nameTerm(arity)));
            }
        }
        List<Term> clauses = new ArrayList<>();
        Atom named = null;
        int funLine = fun.line();
        while (true) {
            Atom name = null;
            int line;
            if (isCategory(VAR) && lookahead(1).category().equals(Atom.of("("))) {
                Token nameToken = next();
                name = (Atom) nameToken.value();
                named = name;
                line = nameToken.line();
            } else {
                line = peek().line();
            }
            expectSymbol("(");
            List<Term> patterns = new ArrayList<>();
            if (!isSymbol(")")) {
                while (true) {
                    patterns.add(pattern(0));
                    if (isSymbol(",")) {
                        next();
                        continue;
                    }
                    break;
                }
            }
            expectSymbol(")");
            List<Term> guards = guard();
            expectSymbol("->");
            List<Term> body = expressionsUntil(";", "end");
            clauses.add(tuple(atom("clause", line), list(patterns),
                listOfLists(guards), list(body)));
            if (isSymbol(";")) {
                next();
                continue;
            }
            break;
        }
        expectSymbol("end");
        if (named != null) {
            return tuple(atom("named_fun", funLine), atom(named.toString(), funLine),
                list(clauses));
        }
        return tuple(atom("fun", funLine), Tuple.of(Atom.of("clauses"), list(clauses)));
    }

    private Term maybeExpression() {
        Token maybe = expectSymbol("maybe");
        List<Term> expressions = new ArrayList<>();
        while (true) {
            Term expression = expr(0);
            if (isSymbol("?=")) {
                Token operator = next();
                Term value = expr(0);
                expression = tuple(atom("maybe_match", operator.line()), expression, value);
            }
            expressions.add(expression);
            if (isSymbol(",")) {
                next();
                continue;
            }
            break;
        }
        if (isSymbol("else")) {
            Token elseToken = next();
            List<Term> clauses = caseClauses("end");
            expectSymbol("end");
            return tuple(atom("maybe", maybe.line()), list(expressions),
                tuple(atom("else", elseToken.line()), list(clauses)));
        }
        expectSymbol("end");
        return tuple(atom("maybe", maybe.line()), list(expressions));
    }

    private List<Term> qualifiers(String close) {
        List<Term> qualifiers = new ArrayList<>();
        while (!isSymbol(close)) {
            Term qualifier = expr(0);
            if (isSymbol("<-")) {
                Token operator = next();
                qualifier = tuple(atom("generate", operator.line()), qualifier, expr(0));
            } else if (isSymbol("<=")) {
                Token operator = next();
                qualifier = tuple(atom("b_generate", operator.line()), qualifier, expr(0));
            } else if (isSymbol("<:-")) {
                Token operator = next();
                qualifier = tuple(atom("generate_strict", operator.line()), qualifier, expr(0));
            } else if (isSymbol("<:=")) {
                Token operator = next();
                qualifier = tuple(atom("b_generate_strict", operator.line()), qualifier, expr(0));
            }
            qualifiers.add(qualifier);
            if (isSymbol(",")) {
                next();
                continue;
            }
            break;
        }
        return qualifiers;
    }

    private List<Term> argumentsAfterOpen() {
        List<Term> arguments = new ArrayList<>();
        if (!isSymbol(")")) {
            while (true) {
                arguments.add(expr(0));
                if (isSymbol(",")) {
                    next();
                    continue;
                }
                break;
            }
        }
        expectSymbol(")");
        return arguments;
    }

    private List<Term> expressionsUntil(String... stops) {
        List<Term> expressions = new ArrayList<>();
        while (!atAny(stops)) {
            expressions.add(expr(0));
            if (isSymbol(",")) {
                next();
                continue;
            }
            break;
        }
        return expressions;
    }

    private boolean atAny(String... stops) {
        for (String stop : stops) {
            if (isSymbol(stop)) {
                return true;
            }
        }
        return isCategory(DOT);
    }

    private String operator(Token token) {
        String name = token.category().toString();
        switch (name) {
        case "=":
        case "!":
        case "orelse":
        case "andalso":
        case "==":
        case "/=":
        case "=<":
        case "<":
        case ">=":
        case ">":
        case "=:=":
        case "=/=":
        case "++":
        case "--":
        case "+":
        case "-":
        case "bor":
        case "bxor":
        case "bsl":
        case "bsr":
        case "or":
        case "xor":
        case "*":
        case "/":
        case "div":
        case "rem":
        case "band":
        case "and":
        case ":":
        case "#":
        case "(":
            return name;
        default:
            return null;
        }
    }

    private String operator() {
        if (!more()) {
            return null;
        }
        return operator(peek());
    }

    private static int precedence(String operator) {
        switch (operator) {
        case "=":
        case "!":
            return 100;
        case "orelse":
            return 150;
        case "andalso":
            return 160;
        case "==":
        case "/=":
        case "=<":
        case "<":
        case ">=":
        case ">":
        case "=:=":
        case "=/=":
            return 200;
        case "++":
        case "--":
            return 300;
        case "+":
        case "-":
        case "bor":
        case "bxor":
        case "bsl":
        case "bsr":
        case "or":
        case "xor":
            return 400;
        case "*":
        case "/":
        case "div":
        case "rem":
        case "band":
        case "and":
            return 500;
        case "#":
            return 700;
        case "(":
            return 750;
        case ":":
            return 800;
        default:
            return -1;
        }
    }

    private static boolean isRightAssociative(String operator) {
        switch (operator) {
        case "=":
        case "!":
        case "orelse":
        case "andalso":
        case "++":
        case "--":
            return true;
        default:
            return false;
        }
    }

    // ------------------------------------------------------------------ types

    private Term topType() {
        if (isCategory(VAR) && lookahead(1).category().equals(Atom.of("::"))) {
            Token variable = next();
            expectSymbol("::");
            return tuple(atom("ann_type", variable.line()), variable(variable), topType());
        }
        Term left = type(0);
        while (isSymbol("|")) {
            next();
            Term right = type(0);
            left = liftUnion(left, right);
        }
        return left;
    }

    private Term liftUnion(Term left, Term right) {
        if (isTuple(right, "type", "union")) {
            Tuple union = right.toTuple();
            List<Term> members = elements(union.element(3));
            members.add(0, left);
            return tuple(atom("type", lineOf(left)), atom("union", lineOf(left)), list(members));
        }
        return tuple(atom("type", lineOf(left)), atom("union", lineOf(left)),
            list(left, right));
    }

    private Term type(int minimum) {
        if (isSymbol("+") || isSymbol("-") || isSymbol("bnot") || isSymbol("not")) {
            Token operator = next();
            return tuple(atom("op", operator.line()),
                Atom.of(operator.category().toString()), type(600));
        }
        Term left = typePrimary();
        while (true) {
            if (!more()) {
                break;
            }
            String name = peek().category().toString();
            int precedence = typePrecedence(name);
            if (precedence < 0 || precedence < minimum) {
                break;
            }
            Token operator = next();
            Term right = type(name.equals("..") ? precedence + 1 : precedence + 1);
            if (name.equals("..")) {
                left = tuple(atom("type", lineOf(left)), atom("range", lineOf(left)),
                    left, right);
            } else {
                left = tuple(atom("op", operator.line()),
                    Atom.of(name), left, right);
            }
        }
        return left;
    }

    private static int typePrecedence(String operator) {
        switch (operator) {
        case "..":
            return 200;
        case "*":
        case "/":
        case "div":
        case "rem":
        case "band":
        case "and":
            return 500;
        case "+":
        case "-":
        case "bor":
        case "bxor":
        case "bsl":
        case "bsr":
        case "or":
        case "xor":
            return 400;
        default:
            return -1;
        }
    }

    private Term typePrimary() {
        Token token = peek();
        if (isCategory(VAR)) {
            next();
            return variable(token);
        }
        if (isCategory(INTEGER) || isCategory(CHAR)) {
            next();
            return tuple(Atom.of(token.category().toString()), Integer.of(token.line()),
                token.value());
        }
        if (isCategory(ATOM)) {
            return atomType();
        }
        if (isSymbol("[")) {
            return listType();
        }
        if (isSymbol("#")) {
            return recordType();
        }
        if (isSymbol("{")) {
            return tupleType();
        }
        if (isSymbol("<<")) {
            return binaryType();
        }
        if (isSymbol("fun")) {
            return funTypeOrEmpty();
        }
        if (isSymbol("(")) {
            next();
            Term type = topType();
            expectSymbol(")");
            return type;
        }
        throw error(token.line(), "unexpected token in type: " + token.category());
    }

    private Term atomType() {
        Token name = next();
        if (isSymbol(":")) {
            next();
            Token function = expectCategory(ATOM);
            expectSymbol("(");
            List<Term> arguments = new ArrayList<>();
            if (!isSymbol(")")) {
                while (true) {
                    arguments.add(topType());
                    if (isSymbol(",")) {
                        next();
                        continue;
                    }
                    break;
                }
            }
            expectSymbol(")");
            return tuple(atom("remote_type", name.line()),
                atom(name.value().toString(), name.line()),
                atom(function.value().toString(), function.line()), list(arguments));
        }
        if (isSymbol("(")) {
            next();
            if (isSymbol(")")) {
                next();
                String typeName = name.value().toString();
                if (typeName.equals("tuple")) {
                    return tuple(atom("type", name.line()), atom("tuple", name.line()),
                        Atom.of("any"));
                }
                if (typeName.equals("map")) {
                    return tuple(atom("type", name.line()), atom("map", name.line()),
                        Atom.of("any"));
                }
                return tuple(atom(typeTag(typeName, 0), name.line()),
                    atom(typeName, name.line()), list(new ArrayList<>()));
            }
            List<Term> arguments = new ArrayList<>();
            while (true) {
                arguments.add(topType());
                if (isSymbol(",")) {
                    next();
                    continue;
                }
                break;
            }
            expectSymbol(")");
            String typeName = name.value().toString();
            String tag = typeName.equals("tuple") ? "type" : typeTag(typeName, arguments.size());
            return tuple(atom(tag, name.line()),
                atom(typeName, name.line()), list(arguments));
        }
        return atomLiteral(name.value().toString(), name.line());
    }

    private Term listType() {
        Token open = expectSymbol("[");
        if (isSymbol("]")) {
            next();
            return tuple(atom("type", open.line()), atom("nil", open.line()),
                list(new ArrayList<>()));
        }
        Term type = topType();
        if (isSymbol(",")) {
            next();
            expectSymbol("...");
            expectSymbol("]");
            return tuple(atom("type", open.line()), atom("nonempty_list", open.line()), type);
        }
        expectSymbol("]");
        return tuple(atom("type", open.line()), atom("list", open.line()), type);
    }

    private Term tupleType() {
        Token open = expectSymbol("{");
        List<Term> types = new ArrayList<>();
        if (!isSymbol("}")) {
            while (true) {
                types.add(topType());
                if (isSymbol(",")) {
                    next();
                    continue;
                }
                break;
            }
        }
        expectSymbol("}");
        return tuple(atom("type", open.line()), atom("tuple", open.line()), list(types));
    }

    private Term recordType() {
        Token hash = expectSymbol("#");
        Token name = expectName();
        expectSymbol("{");
        List<Term> fields = new ArrayList<>();
        fields.add(atom(nameOf(name).toString(), name.line()));
        if (!isSymbol("}")) {
            while (true) {
                Token field = expectCategory(ATOM);
                expectSymbol("::");
                Term type = topType();
                fields.add(tuple(atom("type", field.line()), atom("field_type", field.line()),
                    list(atom(field.value().toString(), field.line()), type)));
                if (isSymbol(",")) {
                    next();
                    continue;
                }
                break;
            }
        }
        expectSymbol("}");
        return tuple(atom("type", hash.line()), atom("record", hash.line()), list(fields));
    }

    private Term binaryType() {
        Token open = expectSymbol("<<");
        Term base = tuple(atom("integer", open.line()), Integer.of(0));
        Term unit = tuple(atom("integer", open.line()), Integer.of(0));
        if (!isSymbol(">>")) {
            if (isCategory(VAR)) {
                next();
                expectSymbol(":");
                base = topType();
            }
            if (isSymbol(",")) {
                next();
                expectCategory(VAR);
                expectSymbol(":");
                expectCategory(VAR);
                expectSymbol("*");
                unit = topType();
            }
        }
        expectSymbol(">>");
        return tuple(atom("type", open.line()), atom("binary", open.line()),
            list(base, unit));
    }

    private Term funTypeOrEmpty() {
        Token fun = expectSymbol("fun");
        expectSymbol("(");
        if (isSymbol(")")) {
            next();
            return tuple(atom("type", fun.line()), atom("fun", fun.line()),
                list(new ArrayList<>()));
        }
        return funTypeAfterOpen(fun);
    }

    /**
     * Parse a function type. The opening parenthesis has either been consumed
     * (when called from fun/1) or is the next token (when called from a spec).
     */
    private Term funType() {
        Token open = expectSymbol("(");
        return funTypeAfterOpen(open);
    }

    private Term funTypeAfterOpen(Token open) {
        Term product;
        if (isSymbol(")")) {
            product = tuple(atom("type", open.line()), atom("product", open.line()),
                list(new ArrayList<>()));
        } else if (isSymbol("...")) {
            next();
            product = tuple(atom("type", open.line()), atom("any", open.line()));
        } else {
            List<Term> arguments = new ArrayList<>();
            while (true) {
                arguments.add(topType());
                if (isSymbol(",")) {
                    next();
                    continue;
                }
                break;
            }
            product = tuple(atom("type", open.line()), atom("product", open.line()),
                list(arguments));
        }
        expectSymbol(")");
        expectSymbol("->");
        Term result = topType();
        return tuple(atom("type", open.line()), atom("fun", open.line()),
            list(product, result));
    }

    private static String typeTag(String name, int arity) {
        return BUILTIN_TYPES.contains(name + "/" + arity) ? "type" : "user_type";
    }

    // --------------------------------------------------------------- helpers

    private static Term variable(String name, int line) {
        return tuple(Atom.of("var"), Integer.of(line), Atom.of(name));
    }

    private Term variable(Token token) {
        return variable(token.value().toString(), token.line());
    }

    /**
     * A tag inside a form, as opposed to an atom expression. The distinction
     * matters: {attribute, Line, module, m} has raw atoms in all four places,
     * while an atom the programmer wrote is {atom, Line, m}.
     */
    private static Term atom(String name, int line) {
        return tuple(Atom.of("atom"), Integer.of(line), Atom.of(name));
    }

    private static Term atomLiteral(String name, int line) {
        return atom(name, line);
    }

    private static Term atom(Atom name, int line) {
        return tuple(Atom.of("atom"), Integer.of(line), name);
    }

    private static Term integer(int value, int line) {
        return tuple(Atom.of("integer"), Integer.of(line), Integer.of(value));
    }

    private static Term tuple(Term... terms) {
        // The parser writes a node as tuple(atom("tag", Line), parts...).
        // This turns that into erl_parse's {tag, Line, parts...} shape once,
        // rather than repeating the line argument at every construction site.
        if (terms.length >= 1 && terms[0] instanceof Tuple) {
            Tuple first = (Tuple) terms[0];
            if (first.arity() == 3 && isAtomValue(first.element(1), "atom")
                && first.element(2) instanceof Integer) {
                Term[] node = new Term[terms.length + 1];
                node[0] = first.element(3);
                node[1] = first.element(2);
                System.arraycopy(terms, 1, node, 2, terms.length - 1);
                return Tuple.of(node);
            }
        }
        return Tuple.of(terms);
    }

    private static Term list(List<Term> terms) {
        return org.jerlang.type.List.of(terms.toArray(new Term[0]));
    }

    private static Term list(Term... terms) {
        return org.jerlang.type.List.of(terms);
    }

    private static Term listOfLists(List<Term> lists) {
        return list(lists);
    }

    private static List<Term> elements(Term term) {
        List<Term> elements = new ArrayList<>();
        org.jerlang.type.List list = term.toList();
        while (list != org.jerlang.type.List.nil) {
            elements.add(list.head());
            list = list.tail();
        }
        return elements;
    }

    private static int lineOf(Term term) {
        if (term instanceof Tuple && ((Tuple) term).arity() >= 2) {
            return ((Integer) ((Tuple) term).element(2)).toInt();
        }
        throw new IllegalArgumentException("no line on " + term);
    }

    private static boolean isTuple(Term term, String tag, String... more) {
        if (!(term instanceof Tuple)) {
            return false;
        }
        Tuple tuple = term.toTuple();
        if (!isAtomValue(tuple.element(1), tag)) {
            return false;
        }
        for (int index = 0; index < more.length; index++) {
            if (tuple.arity() <= index + 2 || !isAtomValue(tuple.element(index + 3), more[index])) {
                return false;
            }
        }
        return true;
    }

    private static boolean isAtomValue(Term term, String name) {
        return term instanceof Atom && term.toString().equals(name);
    }


    private boolean more() {
        return index < tokens.size();
    }

    private Token peek() {
        return tokens.get(Math.min(index, tokens.size() - 1));
    }

    private Token lookahead(int offset) {
        int at = index + offset;
        return tokens.get(Math.min(at, tokens.size() - 1));
    }

    private Token next() {
        if (!more()) {
            throw error(peek().line(), "unexpected end of form");
        }
        return tokens.get(index++);
    }

    private Token previous() {
        return tokens.get(Math.max(0, index - 1));
    }

    private boolean isSymbol(String name) {
        return more() && peek().category().equals(Atom.of(name));
    }

    private boolean isCategory(String name) {
        return more() && peek().category().equals(Atom.of(name));
    }

    private boolean isCategory(Atom name) {
        return more() && peek().category().equals(name);
    }

    private Token expectSymbol(String name) {
        if (!isSymbol(name)) {
            throw error(peek().line(), "expected " + name + ", got " + peek().category());
        }
        return next();
    }

    private Token expectDot() { return expectCategory(DOT); }

    private Token expectCategory(String name) {
        return expectCategory(Atom.of(name));
    }

    private Token expectCategory(Atom name) {
        if (!isCategory(name)) {
            throw error(peek().line(), "expected " + name + ", got " + peek().category());
        }
        return next();
    }

    private Token expectName() {
        if (!(isCategory(ATOM) || isCategory(VAR))) {
            throw error(peek().line(), "expected a name, got " + peek().category());
        }
        return next();
    }

    private Atom nameOf(Token token) {
        if (token.category().equals(ATOM) || token.category().equals(VAR)) {
            return (Atom) token.value();
        }
        return Atom.of(token.category().toString());
    }

    private Term nameTerm(Token token) {
        if (token.category().equals(ATOM)) {
            return atom(token.value().toString(), token.line());
        }
        if (token.category().equals(VAR)) {
            return variable(token);
        }
        if (token.category().equals(INTEGER)) {
            return tuple(Atom.of("integer"), Integer.of(token.line()), token.value());
        }
        throw error(token.line(), "unexpected name " + token.category());
    }

    private ParseException error(int line, String message) {
        return new ParseException(line, message);
    }


    private Term normalise(Term expression) {
        if (expression instanceof Atom) {
            return expression;
        }
        if (expression instanceof org.jerlang.type.List) {
            List<Term> values = elements(expression);
            List<Term> normalised = new ArrayList<>();
            for (Term value : values) {
                normalised.add(normalise(value));
            }
            return list(normalised);
        }
        if (expression instanceof Tuple) {
            Tuple tuple = expression.toTuple();
            if (tuple.arity() < 2) {
                throw error(lineOf(expression), "cannot normalise " + expression);
            }
            String tag = tuple.element(1).toString();
            switch (tag) {
            case "integer":
            case "float":
            case "char":
            case "atom":
            case "string":
                if (tuple.arity() < 3) {
                    break;
                }
                return tuple.element(3);
            case "nil":
                return org.jerlang.type.List.nil;
            case "cons":
                return new org.jerlang.type.List(normalise(tuple.element(3)),
                    (org.jerlang.type.List) normalise(tuple.element(4)));
            case "tuple":
                List<Term> values = elements(tuple.element(3));
                List<Term> converted = new ArrayList<>();
                for (Term value : values) {
                    converted.add(normalise(value));
                }
                return tuple(converted.toArray(new Term[0]));
            case "map":
                Map map = new Map();
                for (Term field : elements(tuple.element(3))) {
                    Tuple pair = field.toTuple();
                    map.set(normalise(pair.element(3)), normalise(pair.element(4)));
                }
                return map;
            default:
                break;
            }
        }
        throw error(expression instanceof Tuple ? lineOf(expression) : 1,
            "cannot normalise " + expression);
    }

    private static final class ParseException extends RuntimeException {

        private static final long serialVersionUID = 1L;

        ParseException(int line, String message) {
            super("line " + line + ": " + message);
        }

    }

}
