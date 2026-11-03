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
 * -include_lib is resolved against the application directories of each
 * library root, the same layout OTP uses under lib: app-version/rest. The root
 * is supplied by the caller because a compiler, unlike epp, has no code path
 * of its own to consult.
 */
public final class Preprocessor {

    private static final Atom DASH = Atom.of("-");
    private static final Atom ATOM = Atom.of("atom");
    private static final Atom VAR = Atom.of("var");
    private static final Atom INTEGER = Atom.of("integer");
    private static final Atom STRING = Atom.of("string");
    private static final Atom DOT = Atom.of("dot");

    private final List<String> includePath;
    private final List<String> libraryPath;
    private final int otpRelease;
    private final Map<String, Macro> macros = new LinkedHashMap<>();
    private Atom functionName;
    private int functionArity;
    private final List<List<Token>> forms = new ArrayList<>();
    private final Set<Path> open = new HashSet<>();
    private final Deque<String> files = new ArrayDeque<>();

    private Preprocessor(List<String> includePath, List<String> libraryPath, int otpRelease) {
        this.includePath = includePath;
        this.libraryPath = libraryPath;
        this.otpRelease = otpRelease;
    }

    /**
     * Preprocess one source, returning the token list of every form, the
     * leading -file form and the final {eof, Line} included.
     */
    public static List<List<Token>> scanFile(Path file) throws IOException {
        return scanFile(file, Collections.emptyList());
    }

    public static List<List<Token>> scanFile(Path file, List<String> includePath) throws IOException {
        return scanFile(file, includePath, Collections.emptyList());
    }

    /**
     * Preprocess one source, resolving -include_lib against the application
     * directories directly under each library root (usually OTP's lib).
     */
    public static List<List<Token>> scanFile(Path file, List<String> includePath,
        List<String> libraryPath) throws IOException {
        return scanFile(file, includePath, libraryPath, 0);
    }

    /**
     * Preprocess with the OTP release the compiler is targeting. epp reports
     * ?OTP_RELEASE as an integer, so the value is a compiler input, not a
     * property of this source tree.
     */
    public static List<List<Token>> scanFile(Path file, List<String> includePath,
        List<String> libraryPath, int otpRelease) throws IOException {
        Preprocessor preprocessor = new Preprocessor(includePath, libraryPath, otpRelease);
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
            String logical = fileName;
            Deque<Condition> conditions = new ArrayDeque<>();

            while (index < tokens.size()) {
                Read read = readForm(tokens, index, offset);
                index = read.next;
                List<Token> form = read.form;
                String directive = directive(form);
                if (directive == null) {
                    if (active) {
                        bindFunction(form);
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
                        include(form, file, tokens, index, offset, eofLine, crlf, false,
                            logical);
                    }
                    break;
                case "include_lib":
                    if (active) {
                        include(form, file, tokens, index, offset, eofLine, crlf, true,
                            logical);
                    }
                    break;
                case "if": {
                    Condition condition = new Condition();
                    condition.parentActive = active;
                    condition.condition = ifExpression(form);
                    conditions.push(condition);
                    active = active && condition.condition;
                    break;
                }
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
                case "feature":
                    // epp consumes -feature and emits no attribute form.
                    break;
                case "file":
                    if (active) {
                        forms.add(generated(expand(form, 0)));
                        offset = fileOffset(form, offset);
                        logical = stringValue(form.get(3));
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
        // epp permits a redefinition (it warns and uses the new body). This
        // matters for headers that define a helper differently under
        // alternative compiler options.
        int arity = parameters == null ? -1 : parameters.size();
        macros.put(macroKey(name, arity), new Macro(name, parameters, body));
    }

    private void undef(List<Token> form) {
        if (form.size() < 6 || !isName(form.get(3))) {
            throw error(form.get(0).line(), "malformed -undef");
        }
        String name = ((Atom) form.get(3).value()).toString();
        macros.keySet().removeIf(key -> belongsTo(key, name));
    }

    private void include(List<Token> form, Path file, List<Token> tokens, int next,
        int offset, int eofLine, boolean crlf, boolean library, String logical)
        throws IOException {
        if (form.size() < 6 || !form.get(3).category().equals(STRING)) {
            throw error(form.get(0).line(), "malformed -include");
        }
        String name = stringValue(form.get(3));
        Path resolved = library ? resolveLibrary(name, form.get(0).line())
            : resolve(file, name, form.get(0).line());
        String included = includeDisplay(resolved);
        forms.add(fileForm(included, 1));
        process(resolved, included, false);
        // epp restores the parent location on the line immediately after the
        // include directive, unless a trailing comment keeps the directive's
        // line as the last token line. For CRLF sources erl_scan reports the
        // directive line instead.
        String parent = files.peek();
        int directive = form.get(0).line() - offset;
        boolean trailingComment = hasTrailingComment(file, directive);
        int line = crlf || trailingComment ? directive : directive + 1;
        forms.add(fileForm(parent, line));
        if (offset != 0) {
            forms.add(generated(fileForm(logical, line + offset, line, true)));
        }
    }

    private static boolean hasTrailingComment(Path file, int line) {
        try {
            java.util.List<String> lines = java.nio.file.Files.readAllLines(file,
                java.nio.charset.StandardCharsets.UTF_8);
            if (line <= 0 || line > lines.size()) {
                return false;
            }
            String text = lines.get(line - 1);
            int dot = text.lastIndexOf('.');
            return dot >= 0 && dot + 1 < text.length();
        } catch (IOException ignored) {
            return false;
        }
    }

    private Path resolve(Path file, String name, int line) {
        Path path = Paths.get(name);
        if (!path.isAbsolute() && file.getParent() != null) {
            path = file.getParent().resolve(path);
        }
        if (Files.isRegularFile(path)) {
            return path;
        }
        for (String directory : includePath) {
            Path candidate = Paths.get(directory).resolve(name);
            if (Files.isRegularFile(candidate)) {
                return candidate;
            }
        }
        throw error(line, "cannot find include '" + name + "'");
    }

    /**
     * An -include_lib name is App/Rest, and epp replaces App with the first
     * App-* directory on the code path. Sorting makes the choice deterministic
     * when a lib root holds more than one version.
     */
    private Path resolveLibrary(String name, int line) throws IOException {
        int slash = name.indexOf('/');
        if (slash <= 0 || slash == name.length() - 1) {
            throw error(line, "malformed -include_lib '" + name + "'");
        }
        String app = name.substring(0, slash);
        String rest = name.substring(slash + 1);
        for (String root : libraryPath) {
            Path directory = Paths.get(root);
            Path exact = directory.resolve(app).resolve(rest);
            if (Files.isRegularFile(exact)) {
                return exact.normalize();
            }
            try (java.util.stream.Stream<Path> applications = Files.list(directory)) {
                List<Path> candidates = applications
                    .filter(Files::isDirectory)
                    .filter(path -> path.getFileName().toString().startsWith(app + "-"))
                    .sorted()
                    .collect(java.util.stream.Collectors.toList());
                for (Path candidate : candidates) {
                    Path include = candidate.resolve(rest);
                    if (Files.isRegularFile(include)) {
                        return include.normalize();
                    }
                }
            } catch (java.io.UncheckedIOException ignored) {
                // A missing or unreadable root is just a miss; the error below
                // names the include, which is what the source can act on.
            }
        }
        throw error(line, "cannot find include_lib '" + name + "'");
    }

    private boolean condition(List<Token> form, boolean positive) {
        if (form.size() < 6 || !form.get(2).category().equals(Atom.of("("))) {
            throw error(form.get(0).line(), "malformed -ifdef");
        }
        Token name = form.get(3);
        if (!name.category().equals(ATOM) && !name.category().equals(VAR)) {
            throw error(form.get(0).line(), "-ifdef needs a macro name");
        }
        boolean defined = isDefined(((Atom) name.value()).toString());
        return positive == defined;
    }

    private boolean ifExpression(List<Token> form) {
        if (form.size() < 6 || !form.get(2).category().equals(Atom.of("("))) {
            throw error(form.get(0).line(), "malformed -if");
        }
        List<Token> expression = new ArrayList<>(form.subList(3, form.size() - 2));
        expression = expand(expression, 0);
        IfParser parser = new IfParser(expression);
        boolean value = parser.parseOr();
        if (parser.index != expression.size()) {
            throw error(form.get(0).line(), "unsupported -if expression");
        }
        return value;
    }

    private final class IfParser {

        private final List<Token> tokens;
        private int index;

        IfParser(List<Token> tokens) {
            this.tokens = tokens;
        }

        boolean parseOr() {
            boolean value = parseAnd();
            while (word("orelse")) {
                index++;
                boolean right = parseAnd();
                value = value || right;
            }
            return value;
        }

        boolean parseAnd() {
            boolean value = parseNot();
            while (word("andalso")) {
                index++;
                boolean right = parseNot();
                value = value && right;
            }
            return value;
        }

        boolean parseNot() {
            if (word("not")) {
                index++;
                return !parseNot();
            }
            return parsePrimary();
        }

        boolean parsePrimary() {
            if (index >= tokens.size()) {
                throw error(0, "empty -if expression");
            }
            if (isSymbol(tokens.get(index), "(")) {
                index++;
                boolean value = parseOr();
                if (index >= tokens.size() || !isSymbol(tokens.get(index), ")")) {
                    throw error(tokens.get(index - 1).line(), "expected ) in -if");
                }
                index++;
                return value;
            }
            Token token = tokens.get(index);
            if (token.category().equals(ATOM)) {
                String name = token.value().toString();
                index++;
                if (name.equals("true")) {
                    return true;
                }
                if (name.equals("false")) {
                    return false;
                }
                if (name.equals("defined")) {
                    if (index >= tokens.size() || !isSymbol(tokens.get(index), "(")) {
                        throw error(token.line(), "defined needs a macro name");
                    }
                    index++;
                    if (index >= tokens.size() || !isName(tokens.get(index))) {
                        throw error(token.line(), "defined needs a macro name");
                    }
                    String macro = ((Atom) tokens.get(index).value()).toString();
                    index++;
                    if (index >= tokens.size() || !isSymbol(tokens.get(index), ")")) {
                        throw error(token.line(), "defined needs a closing parenthesis");
                    }
                    index++;
                    return isDefined(macro);
                }
            }
            throw error(token.line(), "unsupported -if expression");
        }

        private boolean word(String value) {
            if (index >= tokens.size()) {
                return false;
            }
            Token token = tokens.get(index);
            String text = isName(token)
                ? token.value().toString() : token.category().toString();
            return text.equals(value);
        }
    }

    /**
     * ?FUNCTION_NAME and ?FUNCTION_ARITY belong to the function being scanned,
     * not to a macro definition. A function form starts with its name and
     * argument list; the first matching close parenthesis ends that head.
     */
    private void bindFunction(List<Token> form) {
        if (form.size() < 4 || !form.get(0).category().equals(ATOM)
            || !isSymbol(form.get(1), "(")) {
            return;
        }
        int parens = 0;
        int brackets = 0;
        int braces = 0;
        int binaries = 0;
        int commas = 0;
        boolean arguments = false;
        boolean closed = false;
        for (int index = 1; index < form.size(); index++) {
            Token token = form.get(index);
            if (isSymbol(token, ")")) {
                if (parens == 1 && brackets == 0 && braces == 0 && binaries == 0) {
                    closed = true;
                    break;
                }
                parens--;
                continue;
            }
            if (parens == 1 && brackets == 0 && braces == 0 && binaries == 0) {
                if (isSymbol(token, ",")) {
                    commas++;
                } else {
                    // A sole map, list, tuple, or binary argument has no atom
                    // at the top level, but [] and #{} are still arguments.
                    arguments = true;
                }
            }
            if (isSymbol(token, "(")) {
                parens++;
            } else if (isSymbol(token, "[")) {
                brackets++;
            } else if (isSymbol(token, "]")) {
                brackets--;
            } else if (isSymbol(token, "{")) {
                braces++;
            } else if (isSymbol(token, "}")) {
                braces--;
            } else if (isSymbol(token, "<<")) {
                binaries++;
            } else if (isSymbol(token, ">>")) {
                binaries--;
            }
        }
        if (!closed) {
            return;
        }
        functionName = (Atom) form.get(0).value();
        functionArity = arguments ? commas + 1 : 0;
    }

    private void bindModule(List<Token> form) {
        if (form.size() < 5 || !form.get(2).category().equals(Atom.of("("))
            || !form.get(3).category().equals(ATOM)) {
            throw error(form.get(0).line(), "malformed -module");
        }
        Atom name = (Atom) form.get(3).value();
        macros.put(macroKey("MODULE", -1), new Macro("MODULE", null,
            Collections.singletonList(Token.of(ATOM, form.get(3).line(), name))));
        macros.put(macroKey("MODULE_STRING", -1), new Macro("MODULE_STRING", null,
            Collections.singletonList(Token.string(name.toString(), form.get(3).line()))));
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
            if (name.equals("FUNCTION_NAME")) {
                if (functionName == null) {
                    throw error(line, "?FUNCTION_NAME outside a function");
                }
                expanded.add(Token.of(ATOM, line, functionName));
                index++;
                continue;
            }
            if (name.equals("FUNCTION_ARITY")) {
                if (functionName == null) {
                    throw error(line, "?FUNCTION_ARITY outside a function");
                }
                expanded.add(Token.of(INTEGER, line, Integer.of(functionArity)));
                index++;
                continue;
            }
            if (name.equals("OTP_RELEASE")) {
                if (otpRelease <= 0) {
                    throw error(line, "?OTP_RELEASE needs a target release");
                }
                expanded.add(Token.of(INTEGER, line, Integer.of(otpRelease)));
                index++;
                continue;
            }
            int after = index + 2;
            boolean call = after < tokens.size() && isSymbol(tokens.get(after), "(");
            if (!call) {
                Macro macro = macros.get(macroKey(name, -1));
                if (macro == null) {
                    throw error(line, "undefined macro '" + name + "'");
                }
                expanded.addAll(expand(substitute(macro, null, null, line), depth + 1));
                index = after - 1;
            } else {
                Arguments arguments = arguments(tokens, after, line);
                Macro macro = macros.get(macroKey(name, arguments.arguments.size()));
                if (macro == null) {
                    Macro object = macros.get(macroKey(name, -1));
                    if (object != null) {
                        // An object macro can expand to a callable expression;
                        // epp leaves the following argument list in place.
                        expanded.addAll(expand(substitute(object, null, null, line), depth + 1));
                        index = after - 1;
                        continue;
                    }
                    throw error(line, "undefined macro '" + name + "'");
                }
                List<List<Token>> values = new ArrayList<>();
                for (List<Token> argument : arguments.arguments) {
                    values.add(expand(argument, depth + 1));
                }
                expanded.addAll(expand(substitute(macro, values, arguments.arguments, line),
                    depth + 1));
                index = arguments.next - 1;
            }
        }
        return expanded;
    }

    private List<Token> substitute(Macro macro, List<List<Token>> values,
        List<List<Token>> raw, int line) {
        List<Token> substituted = new ArrayList<>();
        int currentLine = line;
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
                List<Token> argument = values.get(position);
                List<Token> source = raw == null ? argument : raw.get(position);
                substituted.add(Token.string(spelling(source), currentLine));
                index += 2;
                continue;
            }
            if (macro.parameters() != null && token.category().equals(VAR)) {
                int position = macro.parameters().indexOf(((Atom) token.value()).toString());
                if (position >= 0) {
                    List<Token> argument = values.get(position);
                    String source = spelling(raw == null ? argument : raw.get(position));
                    List<Token> marked = new ArrayList<>();
                    for (Token value : argument) {
                        marked.add(value.withSourceText(source));
                    }
                    substituted.addAll(marked);
                    if (!argument.isEmpty()) {
                        currentLine = argument.get(argument.size() - 1).line();
                    }
                    continue;
                }
            }
            substituted.add(token.withLine(currentLine));
        }
        return substituted;
    }

    private Arguments arguments(List<Token> tokens, int open, int line) {
        List<List<Token>> arguments = new ArrayList<>();
        List<Token> current = new ArrayList<>();
        int parens = 0;
        int brackets = 0;
        int braces = 0;
        int binaries = 0;
        int blocks = 0;
        int index = open + 1;
        if (index < tokens.size() && isSymbol(tokens.get(index), ")")) {
            return new Arguments(arguments, index + 1);
        }
        while (index < tokens.size()) {
            Token token = tokens.get(index);
            if (isSymbol(token, "(")) {
                parens++;
                current.add(token);
            } else if (isSymbol(token, ")")) {
                if (parens == 0 && brackets == 0 && braces == 0 && binaries == 0
                    && blocks == 0) {
                    arguments.add(current);
                    return new Arguments(arguments, index + 1);
                }
                parens--;
                current.add(token);
            } else if (isSymbol(token, "[")) {
                brackets++;
                current.add(token);
            } else if (isSymbol(token, "]")) {
                brackets--;
                current.add(token);
            } else if (isSymbol(token, "{")) {
                braces++;
                current.add(token);
            } else if (isSymbol(token, "}")) {
                braces--;
                current.add(token);
            } else if (isSymbol(token, "<<")) {
                binaries++;
                current.add(token);
            } else if (isSymbol(token, ">>")) {
                binaries--;
                current.add(token);
            } else if (isBlockEnd(token)) {
                if (blocks > 0) {
                    blocks--;
                }
                current.add(token);
            } else if (isBlockStart(token) || isAnonymousFun(tokens, index)) {
                blocks++;
                current.add(token);
            } else if (isSymbol(token, ",") && parens == 0 && brackets == 0
                && braces == 0 && binaries == 0 && blocks == 0) {
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

    private static boolean isBlockStart(Token token) {
        return isSymbol(token, "begin") || isSymbol(token, "case")
            || isSymbol(token, "if") || isSymbol(token, "receive")
            || isSymbol(token, "try") || isSymbol(token, "maybe")
            || isSymbol(token, "cond") || isSymbol(token, "let");
    }

    private static boolean isBlockEnd(Token token) {
        return isSymbol(token, "end");
    }

    private static boolean isAnonymousFun(List<Token> tokens, int index) {
        if (!isSymbol(tokens.get(index), "fun") || index + 1 >= tokens.size()) {
            return false;
        }
        if (isSymbol(tokens.get(index + 1), "(")) {
            return true;
        }
        return isName(tokens.get(index + 1)) && index + 2 < tokens.size()
            && isSymbol(tokens.get(index + 2), "(");
    }

    /**
     * A macro is named by a variable or an atom: -define(MODULE, ...) and
     * -define(module, ...) are both legal, and ?MODULE names the first.
     */
    private static boolean isName(Token token) {
        return token.category().equals(ATOM) || token.category().equals(VAR);
    }

    private static String macroKey(String name, int arity) {
        return name + "/" + arity;
    }

    private static boolean belongsTo(String key, String name) {
        return key.startsWith(name + "/");
    }

    private boolean isDefined(String name) {
        for (String key : macros.keySet()) {
            if (belongsTo(key, name)) {
                return true;
            }
        }
        return false;
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

    /**
     * The source spelling of an argument. Tokens forwarded through a macro
     * carry the spelling of the argument they originally came from.
     */
    private static String spelling(List<Token> tokens) {
        StringBuilder text = new StringBuilder();
        for (int index = 0; index < tokens.size();) {
            Token token = tokens.get(index);
            String part;
            if (token.sourceText() != null) {
                part = token.sourceText();
                String source = part;
                index++;
                while (index < tokens.size()
                    && source.equals(tokens.get(index).sourceText())) {
                    index++;
                }
            } else {
                part = token.category().equals(INTEGER)
                    ? token.value().toString() : token.text();
                index++;
            }
            if (text.length() > 0) {
                text.append(' ');
            }
            text.append(part);
        }
        return text.toString();
    }

    private static String join(List<Token> tokens) {
        StringBuilder text = new StringBuilder();
        for (Token token : tokens) {
            if (text.length() > 0) {
                text.append(' ');
            }
            text.append(token.category().equals(INTEGER)
                ? token.value().toString() : token.text());
        }
        return text.toString();
    }

    private static List<Token> shift(List<Token> tokens, int delta) {
        List<Token> shifted = new ArrayList<>();
        for (Token token : tokens) {
            shifted.add(token.withLine(token.line() + delta));
        }
        return shifted;
    }

    private static List<Token> generated(List<Token> tokens) {
        List<Token> marked = new ArrayList<>();
        for (Token token : tokens) {
            marked.add(token.asGenerated());
        }
        return marked;
    }

    private static List<Token> fileForm(String file, int line) {
        return fileForm(file, line, line, false);
    }

    private static List<Token> fileForm(String file, int line, int annotationLine,
        boolean generated) {
        List<Token> form = new ArrayList<>();
        form.add(symbol("-", annotationLine));
        form.add(atom("file", annotationLine));
        form.add(symbol("(", annotationLine));
        form.add(Token.string(file, annotationLine));
        form.add(symbol(",", annotationLine));
        form.add(Token.of(INTEGER, annotationLine, Integer.of(line)));
        form.add(symbol(")", annotationLine));
        form.add(Token.of(DOT, annotationLine));
        return generated ? generated(form) : form;
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
