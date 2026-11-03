package org.jerlang.compiler.preprocess;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.jerlang.compiler.scan.Scanner;
import org.jerlang.compiler.scan.Token;
import org.jerlang.type.Atom;
import org.jerlang.type.Integer;

/**
 * = The Erlang preprocessor
 *
 * epp works on tokens, not text: a macro body is the token list between the
 * comma of its -define and the closing parenthesis, and expansion replaces a
 * ?Name or ?Name(...) token sequence with a copy of that body. This class does
 * the same, so the result is the token stream epp:scan_file/2 returns and can
 * be compared with it term for term.
 *
 * The forms it emits include the bookkeeping epp inserts: a leading
 * -file(File, 1), a -file around every include, and a final {eof, Line}. Those
 * are not decoration. A parser that does not see them reports the wrong file
 * and line for everything after an include, and a test that strips them cannot
 * notice.
 *
 * A macro body is re-annotated with the line of the ? that expanded it. That
 * is why a macro written on line two and used on line twenty produces tokens
 * on line twenty: the line belongs to the use, not the definition.
 *
 * Not implemented yet: -include_lib resolution and -file's generated
 * annotations. Both are named in the A3 gap list rather than approximated;
 * -include_lib needs OTP's library layout, and -file needs annotations on
 * tokens rather than plain line numbers.
 */
public final class Preprocessor {

    private static final Atom DASH = Atom.of("-");
    private static final Atom ATOM = Atom.of("atom");
    private static final Atom VAR = Atom.of("var");
    private static final Atom INTEGER = Atom.of("integer");
    private static final Atom STRING = Atom.of("string");
    private static final Atom DOT = Atom.of("dot");

    private final List<String> includePath;
    private final Map<String, Macro> macros = new LinkedHashMap<>();
    private final List<List<Token>> forms = new ArrayList<>();
    private final Set<Path> open = new HashSet<>();
    private final Deque<String> files = new ArrayDeque<>();

    private Preprocessor(List<String> includePath) {
        this.includePath = includePath;
    }

    /**
     * Preprocess one source, returning the token list of every form, the
     * leading -file form and the final {eof, Line} included.
     */
    public static List<List<Token>> scanFile(Path file) throws IOException {
        return scanFile(file, Collections.emptyList());
    }

    public static List<List<Token>> scanFile(Path file, List<String> includePath) throws IOException {
        Preprocessor preprocessor = new Preprocessor(includePath);
        // epp records the name it was given, and the oracle is given absolute
        // paths. Resolving here keeps ?FILE and every -file form absolute even
        // when the caller passed a path relative to the working directory.
        Path absolute = file.toAbsolutePath().normalize();
        String name = display(absolute);
        preprocessor.forms.add(fileForm(name, 1));
        preprocessor.process(absolute, name, true);
        return preprocessor.forms;
    }

    private void process(Path file, String fileName, boolean top) throws IOException {
        Path key = file.toAbsolutePath().normalize();
        if (!open.add(key)) {
            throw new PreprocessException(display(file), 1, "include cycle");
        }
        if (open.size() > 64) {
            throw new PreprocessException(display(file), 1, "includes nested too deeply");
        }
        files.push(fileName);
        try {
            String source = new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
            List<Token> tokens = Scanner.scan(source);
            int eofLine = 1 + newlines(source);
            boolean crlf = source.contains("\r\n");
            int offset = 0;
            int index = 0;
            boolean active = true;
            Deque<Condition> conditions = new ArrayDeque<>();

            while (index < tokens.size()) {
                Read read = readForm(tokens, index, offset);
                index = read.next;
                List<Token> form = read.form;
                String directive = directive(form);
                if (directive == null) {
                    if (active) {
                        forms.add(expand(form, 0));
                    }
                    continue;
                }
                switch (directive) {
                case "define":
                    if (active) {
                        define(form);
                    }
                    break;
                case "undef":
                    if (active) {
                        undef(form);
                    }
                    break;
                case "include":
                    if (active) {
                        include(form, file, tokens, index, offset, eofLine, crlf);
                    }
                    break;
                case "include_lib":
                    if (active) {
                        throw error(form.get(0).line(), "-include_lib is not supported yet");
                    }
                    break;
                case "ifdef":
                case "ifndef": {
                    Condition condition = new Condition();
                    condition.parentActive = active;
                    condition.condition = condition(form, directive.equals("ifdef"));
                    conditions.push(condition);
                    active = active && condition.condition;
                    break;
                }
                case "else": {
                    if (conditions.isEmpty()) {
                        throw error(form.get(0).line(), "-else without -ifdef");
                    }
                    Condition condition = conditions.peek();
                    if (condition.elseSeen) {
                        throw error(form.get(0).line(), "second -else for one -ifdef");
                    }
                    condition.elseSeen = true;
                    active = condition.parentActive && !condition.condition;
                    break;
                }
                case "endif": {
                    if (conditions.isEmpty()) {
                        throw error(form.get(0).line(), "-endif without -ifdef");
                    }
                    Condition condition = conditions.pop();
                    active = condition.parentActive;
                    break;
                }
                case "file":
                    if (active) {
                        forms.add(expand(form, 0));
                        offset = fileOffset(form, offset);
                    }
                    break;
                case "module":
                    if (active) {
                        List<Token> expanded = expand(form, 0);
                        forms.add(expanded);
                        bindModule(expanded);
                    }
                    break;
                default:
                    if (active) {
                        forms.add(expand(form, 0));
                    }
                    break;
                }
            }
            if (!conditions.isEmpty()) {
                throw error(eofLine + offset, "unterminated -ifdef");
            }
            if (top) {
                forms.add(Collections.singletonList(Token.eof(eofLine + offset)));
            }
        } finally {
            open.remove(key);
            files.pop();
        }
    }

    private void define(List<Token> form) {
        int line = form.get(0).line();
        if (form.size() < 6 || !isSymbol(form.get(2), "(")
            || !isName(form.get(3))
            || !isSymbol(form.get(form.size() - 2), ")")) {
            throw error(line, "malformed -define");
        }
        String name = ((Atom) form.get(3).value()).toString();
        int index = 4;
        List<String> parameters = null;
        if (isSymbol(form.get(index), "(")) {
            parameters = new ArrayList<>();
            index++;
            if (!isSymbol(form.get(index), ")")) {
                while (true) {
                    Token parameter = form.get(index);
                    if (!parameter.category().equals(VAR) && !parameter.category().equals(ATOM)) {
                        throw error(line, "a macro parameter must be a variable");
                    }
                    parameters.add(((Atom) parameter.value()).toString());
                    index++;
                    if (isSymbol(form.get(index), ")")) {
                        break;
                    }
                    if (!isSymbol(form.get(index), ",")) {
                        throw error(line, "expected , between macro parameters");
                    }
                    index++;
                }
            }
            index++;
        }
        if (!isSymbol(form.get(index), ",")) {
            throw error(line, "expected , before the macro body");
        }
        List<Token> body = new ArrayList<>(form.subList(index + 1, form.size() - 2));
        if (macros.containsKey(name)) {
            throw error(line, "macro '" + name + "' is already defined");
        }
        macros.put(name, new Macro(name, parameters, body));
    }

    private void undef(List<Token> form) {
        if (form.size() < 6 || !isName(form.get(3))) {
            throw error(form.get(0).line(), "malformed -undef");
        }
        macros.remove(((Atom) form.get(3).value()).toString());
    }

    private void include(List<Token> form, Path file, List<Token> tokens, int next,
        int offset, int eofLine, boolean crlf) throws IOException {
        if (form.size() < 6 || !form.get(3).category().equals(STRING)) {
            throw error(form.get(0).line(), "malformed -include");
        }
        String name = stringValue(form.get(3));
        Path resolved = resolve(file, name, form.get(0).line());
        String included = includeDisplay(resolved);
        forms.add(fileForm(included, 1));
        process(resolved, included, false);
        // epp leaves its location on the include line when the parent source
        // uses CRLF, and on the line of the next token when it uses LF. That
        // is a quirk of erl_scan's CRLF handling, but the -file form is part
        // of the token stream, so it has to be reproduced.
        int line = crlf ? form.get(0).line()
            : next < tokens.size() ? tokens.get(next).line() + offset : eofLine + offset;
        forms.add(fileForm(files.peek(), line));
    }

    private Path resolve(Path file, String name, int line) {
        Path path = Paths.get(name);
        if (!path.isAbsolute() && file.getParent() != null) {
            path = file.getParent().resolve(path);
        }
        if (Files.isRegularFile(path)) {
            return path.normalize();
        }
        for (String directory : includePath) {
            Path candidate = Paths.get(directory).resolve(name);
            if (Files.isRegularFile(candidate)) {
                return candidate.normalize();
            }
        }
        throw error(line, "cannot find include '" + name + "'");
    }

    private boolean condition(List<Token> form, boolean positive) {
        if (form.size() < 6 || !form.get(2).category().equals(Atom.of("("))) {
            throw error(form.get(0).line(), "malformed -ifdef");
        }
        Token name = form.get(3);
        if (!name.category().equals(ATOM) && !name.category().equals(VAR)) {
            throw error(form.get(0).line(), "-ifdef needs a macro name");
        }
        boolean defined = macros.containsKey(((Atom) name.value()).toString());
        return positive == defined;
    }

    private void bindModule(List<Token> form) {
        if (form.size() < 5 || !form.get(2).category().equals(Atom.of("("))
            || !form.get(3).category().equals(ATOM)) {
            throw error(form.get(0).line(), "malformed -module");
        }
        Atom name = (Atom) form.get(3).value();
        macros.put("MODULE", new Macro("MODULE", null,
            Collections.singletonList(Token.of(ATOM, form.get(3).line(), name))));
    }

    private int fileOffset(List<Token> form, int offset) {
        if (form.size() < 8 || !form.get(5).category().equals(INTEGER)) {
            throw error(form.get(0).line(), "malformed -file");
        }
        int line = ((Integer) form.get(5).value()).toInt();
        // form.get(0).line() already contains the old offset; the next raw
        // source line must become line + 1, so solve for the new offset.
        return line + offset - form.get(0).line();
    }

    private List<Token> expand(List<Token> tokens, int depth) {
        if (depth > 64) {
            throw error(tokens.isEmpty() ? 1 : tokens.get(0).line(), "macro expansion is too deep");
        }
        List<Token> expanded = new ArrayList<>();
        for (int index = 0; index < tokens.size(); index++) {
            Token token = tokens.get(index);
            if (!isSymbol(token, "?")) {
                expanded.add(token);
                continue;
            }
            int line = token.line();
            if (index + 1 >= tokens.size()) {
                throw error(line, "? at the end of a form");
            }
            Token nameToken = tokens.get(index + 1);
            if (isSymbol(nameToken, "?")) {
                throw error(line, "?? is only valid inside a macro body");
            }
            if (!isName(nameToken)) {
                throw error(line, "? must be followed by a macro name");
            }
            String name = ((Atom) nameToken.value()).toString();
            if (name.equals("FILE")) {
                expanded.add(Token.string(currentFile(), line));
                index++;
                continue;
            }
            if (name.equals("LINE")) {
                expanded.add(Token.of(INTEGER, line, Integer.of(line)));
                index++;
                continue;
            }
            Macro macro = macros.get(name);
            if (macro == null) {
                throw error(line, "undefined macro '" + name + "'");
            }
            int after = index + 2;
            boolean call = after < tokens.size() && isSymbol(tokens.get(after), "(");
            if (macro.parameters() == null) {
                if (call) {
                    throw error(line, "macro '" + name + "' does not take arguments");
                }
                expanded.addAll(expand(substitute(macro, null, line), depth + 1));
                index = after - 1;
            } else {
                if (!call) {
                    throw error(line, "macro '" + name + "' needs arguments");
                }
                Arguments arguments = arguments(tokens, after, line);
                if (arguments.arguments.size() != macro.parameters().size()) {
                    throw error(line, "macro '" + name + "' expects "
                        + macro.parameters().size() + " arguments, got "
                        + arguments.arguments.size());
                }
                List<List<Token>> values = new ArrayList<>();
                for (List<Token> argument : arguments.arguments) {
                    values.add(expand(argument, depth + 1));
                }
                expanded.addAll(expand(substitute(macro, values, line), depth + 1));
                index = arguments.next - 1;
            }
        }
        return expanded;
    }

    private List<Token> substitute(Macro macro, List<List<Token>> values, int line) {
        List<Token> substituted = new ArrayList<>();
        for (int index = 0; index < macro.body().size(); index++) {
            Token token = macro.body().get(index);
            if (isSymbol(token, "?") && index + 2 < macro.body().size()
                && isSymbol(macro.body().get(index + 1), "?")
                && macro.body().get(index + 2).category().equals(VAR)) {
                String parameter = ((Atom) macro.body().get(index + 2).value()).toString();
                int position = macro.parameters() == null ? -1 : macro.parameters().indexOf(parameter);
                if (position < 0) {
                    throw error(line, "??" + parameter + " is not a macro parameter");
                }
                substituted.add(Token.string(join(values.get(position)), line));
                index += 2;
                continue;
            }
            if (macro.parameters() != null && token.category().equals(VAR)) {
                int position = macro.parameters().indexOf(((Atom) token.value()).toString());
                if (position >= 0) {
                    substituted.addAll(values.get(position));
                    continue;
                }
            }
            substituted.add(token.withLine(line));
        }
        return substituted;
    }

    private Arguments arguments(List<Token> tokens, int open, int line) {
        List<List<Token>> arguments = new ArrayList<>();
        List<Token> current = new ArrayList<>();
        int depth = 0;
        int index = open + 1;
        if (index < tokens.size() && isSymbol(tokens.get(index), ")")) {
            return new Arguments(arguments, index + 1);
        }
        while (index < tokens.size()) {
            Token token = tokens.get(index);
            if (isSymbol(token, "(")) {
                depth++;
                current.add(token);
            } else if (isSymbol(token, ")")) {
                if (depth == 0) {
                    arguments.add(current);
                    return new Arguments(arguments, index + 1);
                }
                depth--;
                current.add(token);
            } else if (isSymbol(token, ",") && depth == 0) {
                arguments.add(current);
                current = new ArrayList<>();
            } else {
                current.add(token);
            }
            index++;
        }
        throw error(line, "macro arguments are not terminated");
    }

    private Read readForm(List<Token> tokens, int start, int offset) {
        List<Token> form = new ArrayList<>();
        int index = start;
        while (index < tokens.size()) {
            Token token = tokens.get(index++);
            form.add(token.withLine(token.line() + offset));
            if (token.category().equals(DOT)) {
                return new Read(form, index);
            }
        }
        int line = form.isEmpty() ? 1 : form.get(form.size() - 1).line();
        throw error(line, "form is not terminated by a dot");
    }

    private static String directive(List<Token> form) {
        if (form.size() < 2 || !form.get(0).category().equals(DASH)) {
            return null;
        }
        Token name = form.get(1);
        if (name.category().equals(ATOM) || name.category().equals(VAR)) {
            return ((Atom) name.value()).toString();
        }
        // else and if are scanner keywords, so their name is the category and
        // there is no value to read. Without this case -else is emitted as a
        // form instead of changing which branch is active.
        return name.category().toString();
    }

    private static boolean isSymbol(Token token, String name) {
        return token.category().equals(Atom.of(name));
    }

    /**
     * A macro is named by a variable or an atom: -define(MODULE, ...) and
     * -define(module, ...) are both legal, and ?MODULE names the first.
     */
    private static boolean isName(Token token) {
        return token.category().equals(ATOM) || token.category().equals(VAR);
    }

    private String currentFile() {
        return files.peek();
    }

    private PreprocessException error(int line, String message) {
        return new PreprocessException(currentFile(), line, message);
    }

    private static int newlines(String source) {
        int count = 0;
        for (int index = 0; index < source.length(); index++) {
            if (source.charAt(index) == '\n') {
                count++;
            }
        }
        return count;
    }

    private static String display(Path path) {
        return path.toString().replace('\\', '/');
    }

    /**
     * Erlang's filename:join/2 lowercases the drive letter on Windows, and epp
     * builds the -file name of an include with it. Matching that matters: the
     * name is part of the token stream and later part of every stacktrace line
     * the parsed forms carry.
     */
    private static String includeDisplay(Path path) {
        String name = display(path);
        if (name.length() > 1 && name.charAt(1) == ':' && Character.isLetter(name.charAt(0))) {
            return Character.toLowerCase(name.charAt(0)) + name.substring(1);
        }
        return name;
    }

    private static String stringValue(Token token) {
        StringBuilder value = new StringBuilder();
        org.jerlang.type.List list = token.value().toList();
        while (list != org.jerlang.type.List.nil) {
            value.appendCodePoint(((Integer) list.head()).toInt());
            list = list.tail();
        }
        return value.toString();
    }

    private static String join(List<Token> tokens) {
        StringBuilder text = new StringBuilder();
        for (Token token : tokens) {
            if (text.length() > 0) {
                text.append(' ');
            }
            text.append(token.text());
        }
        return text.toString();
    }

    private static List<Token> fileForm(String file, int line) {
        List<Token> form = new ArrayList<>();
        form.add(symbol("-", line));
        form.add(atom("file", line));
        form.add(symbol("(", line));
        form.add(Token.string(file, line));
        form.add(symbol(",", line));
        form.add(Token.of(INTEGER, line, Integer.of(line)));
        form.add(symbol(")", line));
        form.add(Token.of(DOT, line));
        return form;
    }

    private static Token symbol(String name, int line) {
        return Token.of(Atom.of(name), line);
    }

    private static Token atom(String name, int line) {
        return Token.of(ATOM, line, Atom.of(name));
    }

    private static final class Macro {

        private final String name;
        private final List<String> parameters;
        private final List<Token> body;

        Macro(String name, List<String> parameters, List<Token> body) {
            this.name = name;
            this.parameters = parameters;
            this.body = body;
        }

        String name() {
            return name;
        }

        List<String> parameters() {
            return parameters;
        }

        List<Token> body() {
            return body;
        }

    }

    private static final class Condition {

        private boolean parentActive;
        private boolean condition;
        private boolean elseSeen;

    }

    private static final class Arguments {

        private final List<List<Token>> arguments;
        private final int next;

        Arguments(List<List<Token>> arguments, int next) {
            this.arguments = arguments;
            this.next = next;
        }

    }

    private static final class Read {

        private final List<Token> form;
        private final int next;

        Read(List<Token> form, int next) {
            this.form = form;
            this.next = next;
        }

    }

}
