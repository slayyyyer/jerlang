package org.jerlang.compiler.lint;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

import org.jerlang.compiler.parse.Parser;
import org.jerlang.compiler.preprocess.Preprocessor;
import org.jerlang.type.Atom;
import org.jerlang.type.Integer;
import org.jerlang.type.Term;
import org.jerlang.type.Tuple;

/**
 * = The linter
 *
 * The module pass indexes definitions, exports, imports and compiler options.
 * Function bodies go through {@link FunctionScope}, which owns variable flow,
 * guards, map keys and binary patterns. The division matters: a call is only
 * undefined after the whole module has been indexed, while a variable is only
 * unsafe after its own branch structure has been folded.
 *
 * Lines come from the forms. The abstract format does not retain columns, so
 * the result is line-oriented even where erlc prints a column too.
 */
public final class Linter {

    private static final class Signature {

        private final Atom name;
        private final int arity;

        private Signature(Atom name, int arity) {
            this.name = name;
            this.arity = arity;
        }

        @Override
        public boolean equals(Object object) {
            if (!(object instanceof Signature)) {
                return false;
            }
            Signature other = (Signature) object;
            return name.equals(other.name) && arity == other.arity;
        }

        @Override
        public int hashCode() {
            return Objects.hash(name, arity);
        }

        @Override
        public String toString() {
            return name + "/" + arity;
        }

    }

    private static final class FunctionDefinition {

        private final Signature signature;
        private final int line;
        private final Term form;

        private FunctionDefinition(Signature signature, int line, Term form) {
            this.signature = signature;
            this.line = line;
            this.form = form;
        }

    }

    private static final class Export {

        private final Signature signature;
        private final int line;

        private Export(Signature signature, int line) {
            this.signature = signature;
            this.line = line;
        }

    }

    private static final class OnLoad {

        private final Signature signature;
        private final int line;

        private OnLoad(Signature signature, int line) {
            this.signature = signature;
            this.line = line;
        }

    }

    /**
     * Lint one source through the same scanner, preprocessor and parser that
     * will feed code generation.
     */
    public static List<Diagnostic> lint(Path source) throws IOException {
        return lint(Parser.parseForms(Preprocessor.scanFile(source)));
    }

    /**
     * Lint a source with the include and library paths used by a build. The
     * corpus oracle needs this: an installed OTP tree keeps headers next to
     * the application, not next to every source.
     */
    public static List<Diagnostic> lint(Path source, List<String> includes,
        List<String> libraries, int otpRelease) throws IOException {
        return lint(Parser.parseForms(Preprocessor.scanFile(source, includes,
            libraries, otpRelease)));
    }

    /**
     * Lint already parsed forms. This is the entry point for a caller that
     * keeps forms in memory.
     */
    public static List<Diagnostic> lint(List<Term> forms) {
        return new Linter(forms).analyse();
    }

    private final List<Term> forms;
    private final List<Diagnostic> diagnostics = new ArrayList<>();
    private final LinkedHashMap<Signature, FunctionDefinition> functions =
        new LinkedHashMap<>();
    private final List<Export> exports = new ArrayList<>();
    private final Set<Signature> used = new LinkedHashSet<>();
    private final Set<Signature> imported = new LinkedHashSet<>();
    private final Set<Signature> exported = new LinkedHashSet<>();
    private final List<OnLoad> onLoads = new ArrayList<>();
    private final Set<Signature> quietFunctions = new LinkedHashSet<>();
    private boolean exportAll;
    private int exportAllLine;
    private boolean nowarnExportAll;
    private boolean nowarnUnusedFunction;
    private boolean nowarnUnusedVariables;
    private boolean nowarnShadowVariables;
    private boolean noAutoImport;

    private final LintEnvironment environment = new LintEnvironment() {
        @Override
        public void error(int line, String message) {
            diagnostics.add(Diagnostic.error(line, message));
        }

        @Override
        public void warning(int line, String message) {
            diagnostics.add(Diagnostic.warning(line, message));
        }

        @Override
        public void localUse(int line, Atom name, int arity, boolean reference) {
            Linter.this.localUse(line, new Signature(name, arity));
        }

        @Override
        public boolean warnUnusedVariables() {
            return !nowarnUnusedVariables;
        }

        @Override
        public boolean warnShadowVariables() {
            return !nowarnShadowVariables;
        }
    };

    private Linter(List<Term> forms) {
        this.forms = forms;
    }

    private List<Diagnostic> analyse() {
        index();
        for (FunctionDefinition function : functions.values()) {
            new FunctionScope(environment).analyse(function.form);
        }
        checkExports();
        checkOnLoad();
        checkFunctions();
        diagnostics.sort(Comparator.comparingInt(Diagnostic::line));
        return diagnostics;
    }

    private void index() {
        for (Term form : forms) {
            if (!(form instanceof Tuple)) {
                continue;
            }
            Tuple tuple = (Tuple) form;
            if (tuple.arity() < 2) {
                continue;
            }
            Term tag = tuple.element(1);
            if (isAtom(tag, "attribute")) {
                attribute(tuple);
            } else if (isAtom(tag, "function")) {
                function(tuple);
            }
        }
    }

    private void attribute(Tuple tuple) {
        if (tuple.arity() < 4 || !(tuple.element(3) instanceof Atom)) {
            return;
        }
        int line = lineOf(tuple.element(2));
        String name = tuple.element(3).toString();
        Term value = tuple.element(4);
        switch (name) {
        case "export":
            export(line, value);
            break;
        case "import":
            imports(value);
            break;
        case "compile":
            compile(line, value);
            break;
        case "on_load":
            onLoad(line, value);
            break;
        default:
            break;
        }
    }

    private void export(int line, Term value) {
        for (Term entry : elements(value)) {
            Signature signature = pairSignature(entry);
            if (signature == null) {
                continue;
            }
            if (!exported.add(signature)) {
                diagnostics.add(Diagnostic.warning(line,
                    "function " + signature + " already exported"));
            }
            exports.add(new Export(signature, line));
            used.add(signature);
        }
    }

    private void imports(Term value) {
        if (!(value instanceof Tuple)) {
            return;
        }
        Tuple declaration = (Tuple) value;
        if (declaration.arity() < 2) {
            return;
        }
        for (Term entry : elements(declaration.element(2))) {
            Signature signature = pairSignature(entry);
            if (signature != null) {
                imported.add(signature);
            }
        }
    }

    private void compile(int line, Term value) {
        for (Term option : options(value)) {
            if (isAtom(option, "export_all")
                || isTuple(option, "export_all", 2)) {
                if (!exportAll) {
                    exportAll = true;
                    exportAllLine = line;
                }
            } else if (isAtom(option, "nowarn_export_all")) {
                nowarnExportAll = true;
            } else if (isAtom(option, "nowarn_unused_function")) {
                nowarnUnusedFunction = true;
            } else if (isAtom(option, "nowarn_unused_vars")) {
                nowarnUnusedVariables = true;
            } else if (isAtom(option, "nowarn_shadow_vars")) {
                nowarnShadowVariables = true;
            } else if (isAtom(option, "no_auto_import")) {
                noAutoImport = true;
            } else if (isTuple(option, "nowarn_unused_function", 2)) {
                Term entries = ((Tuple) option).element(2);
                if (!(entries instanceof org.jerlang.type.List)) {
                    nowarnUnusedFunction = true;
                } else {
                    for (Term entry : elements(entries)) {
                        Signature signature = pairSignature(entry);
                        if (signature != null) {
                            quietFunctions.add(signature);
                        }
                    }
                }
            }
        }
    }

    private void onLoad(int line, Term value) {
        Signature signature = pairSignature(value);
        if (signature == null) {
            return;
        }
        onLoads.add(new OnLoad(signature, line));
    }

    private void function(Tuple tuple) {
        if (tuple.arity() < 5
            || !(tuple.element(3) instanceof Atom)
            || !(tuple.element(4) instanceof Integer)) {
            return;
        }
        Signature signature = new Signature((Atom) tuple.element(3),
            ((Integer) tuple.element(4)).toInt());
        int line = lineOf(tuple.element(2));
        if (functions.containsKey(signature)) {
            diagnostics.add(Diagnostic.error(line,
                "function " + signature + " already defined"));
        } else {
            functions.put(signature,
                new FunctionDefinition(signature, line, tuple));
        }
    }

    private void localUse(int line, Signature signature) {
        boolean generated = signature.name.toString().equals("module_info")
            && (signature.arity == 0 || signature.arity == 1);
        if (generated || (signature.name.toString().equals("record_info")
            && signature.arity == 2)) {
            return;
        }
        if (functions.containsKey(signature)) {
            used.add(signature);
        } else if (imported.contains(signature)) {
            return;
        } else if (!noAutoImport && Builtins.automatic(signature.name.toString(),
            signature.arity)) {
            return;
        } else {
            diagnostics.add(Diagnostic.error(line,
                "function " + signature + " undefined"));
        }
    }

    private void checkExports() {
        for (Export export : exports) {
            if (!functions.containsKey(export.signature)) {
                diagnostics.add(Diagnostic.error(export.line,
                    "function " + export.signature + " undefined"));
            }
        }
    }

    private void checkOnLoad() {
        for (OnLoad onLoad : onLoads) {
            if (onLoad.signature.arity != 0) {
                diagnostics.add(Diagnostic.error(onLoad.line,
                    "function " + onLoad.signature
                        + " has wrong arity (must be 0)"));
                if (functions.containsKey(onLoad.signature)) {
                    used.add(onLoad.signature);
                }
            } else if (!functions.containsKey(onLoad.signature)) {
                diagnostics.add(Diagnostic.error(onLoad.line,
                    "function " + onLoad.signature + " undefined"));
            } else {
                used.add(onLoad.signature);
            }
        }
    }

    private void checkFunctions() {
        if (exportAll) {
            if (!nowarnExportAll) {
                diagnostics.add(Diagnostic.warning(exportAllLine,
                    "export_all flag enabled - all functions will be exported"));
            }
            return;
        }
        if (nowarnUnusedFunction) {
            return;
        }
        for (FunctionDefinition function : functions.values()) {
            if (!used.contains(function.signature)
                && !quietFunctions.contains(function.signature)) {
                diagnostics.add(Diagnostic.warning(function.line,
                    "function " + function.signature + " is unused"));
            }
        }
    }

    private static Signature pairSignature(Term term) {
        if (!(term instanceof Tuple)) {
            return null;
        }
        Tuple pair = (Tuple) term;
        if (pair.arity() != 2
            || !(pair.element(1) instanceof Atom)
            || !(pair.element(2) instanceof Integer)) {
            return null;
        }
        return new Signature((Atom) pair.element(1),
            ((Integer) pair.element(2)).toInt());
    }

    private static List<Term> options(Term value) {
        if (value instanceof org.jerlang.type.List) {
            return elements(value);
        }
        List<Term> single = new ArrayList<>();
        single.add(value);
        return single;
    }

    private static List<Term> elements(Term term) {
        List<Term> values = new ArrayList<>();
        if (!(term instanceof org.jerlang.type.List)) {
            return values;
        }
        org.jerlang.type.List list = term.toList();
        while (list != org.jerlang.type.List.nil) {
            values.add(list.head());
            list = list.tail();
        }
        return values;
    }

    private static boolean isAtom(Term term, String name) {
        return term instanceof Atom && name.equals(term.toString());
    }

    private static boolean isTuple(Term term, String tag, int arity) {
        return term instanceof Tuple
            && ((Tuple) term).arity() == arity
            && isAtom(((Tuple) term).element(1), tag);
    }

    private static int lineOf(Term annotation) {
        if (annotation instanceof Integer) {
            return ((Integer) annotation).toInt();
        }
        if (annotation instanceof org.jerlang.type.List) {
            for (Term item : elements(annotation)) {
                if (isTuple(item, "location", 2)
                    && ((Tuple) item).element(2) instanceof Integer) {
                    return ((Integer) ((Tuple) item).element(2)).toInt();
                }
            }
        }
        return 0;
    }

}
