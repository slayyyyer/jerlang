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
 * The first A4 slice checks the module's shape rather than its expressions:
 * duplicate definitions, undefined exports, unused local functions, on_load
 * references and export_all. Those rules need the function table and a walk
 * over the abstract forms, but no variable flow, so they are a foundation the
 * expression rules can build on.
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

    /**
     * Lint one source through the same scanner, preprocessor and parser that
     * will feed code generation.
     */
    public static List<Diagnostic> lint(Path source) throws IOException {
        return lint(Parser.parseForms(Preprocessor.scanFile(source)));
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
    private boolean exportAll;
    private int exportAllLine;

    private Linter(List<Term> forms) {
        this.forms = forms;
    }

    private List<Diagnostic> analyse() {
        index();
        markUses();
        checkExports();
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
        case "compile":
            compile(line, value);
            break;
        case "on_load":
            onLoad(value);
            break;
        default:
            break;
        }
    }

    private void export(int line, Term value) {
        for (Term entry : elements(value)) {
            Signature signature = pairSignature(entry);
            if (signature != null) {
                exports.add(new Export(signature, line));
                used.add(signature);
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
            }
        }
    }

    private void onLoad(Term value) {
        Signature signature = pairSignature(value);
        if (signature != null) {
            used.add(signature);
        }
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

    private void markUses() {
        for (FunctionDefinition function : functions.values()) {
            collectUses(function.form);
        }
    }

    /**
     * Find local calls and fun references. Calls to BIFs are added to the
     * used set too; they simply never match a function definition.
     */
    private void collectUses(Term term) {
        if (term instanceof org.jerlang.type.Str) {
            return;
        }
        if (term instanceof org.jerlang.type.List) {
            org.jerlang.type.List list = term.toList();
            while (list != org.jerlang.type.List.nil) {
                collectUses(list.head());
                list = list.tail();
            }
            return;
        }
        if (!(term instanceof Tuple)) {
            return;
        }
        Tuple tuple = (Tuple) term;
        if (tuple.arity() == 4 && isAtom(tuple.element(1), "call")) {
            localCall(tuple);
        } else if (tuple.arity() == 3 && isAtom(tuple.element(1), "fun")) {
            Signature signature = funReference(tuple.element(3));
            if (signature != null) {
                used.add(signature);
            }
        }
        for (int index = 1; index <= tuple.arity(); index++) {
            collectUses(tuple.element(index));
        }
    }

    private void localCall(Tuple call) {
        if (!(call.element(3) instanceof Tuple)) {
            return;
        }
        Tuple target = (Tuple) call.element(3);
        if (target.arity() != 3 || !isAtom(target.element(1), "atom")
            || !(target.element(3) instanceof Atom)) {
            return;
        }
        int arity = elements(call.element(4)).size();
        used.add(new Signature((Atom) target.element(3), arity));
    }

    private static Signature funReference(Term term) {
        if (!(term instanceof Tuple)) {
            return null;
        }
        Tuple reference = (Tuple) term;
        if (reference.arity() != 3 || !isAtom(reference.element(1), "function")) {
            return null;
        }
        Term name = reference.element(2);
        Term arity = reference.element(3);
        if (name instanceof Atom && arity instanceof Integer) {
            return new Signature((Atom) name, ((Integer) arity).toInt());
        }
        if (name instanceof Tuple && arity instanceof Tuple
            && isAtom(((Tuple) name).element(1), "atom")
            && isAtom(((Tuple) arity).element(1), "integer")) {
            Tuple nameTuple = (Tuple) name;
            Tuple arityTuple = (Tuple) arity;
            if (nameTuple.element(3) instanceof Atom
                && arityTuple.element(3) instanceof Integer) {
                return new Signature((Atom) nameTuple.element(3),
                    ((Integer) arityTuple.element(3)).toInt());
            }
        }
        return null;
    }

    private void checkExports() {
        for (Export export : exports) {
            if (!functions.containsKey(export.signature)) {
                diagnostics.add(Diagnostic.error(export.line,
                    "function " + export.signature + " undefined"));
            }
        }
    }

    private void checkFunctions() {
        if (exportAll) {
            diagnostics.add(Diagnostic.warning(exportAllLine,
                "export_all flag enabled - all functions will be exported"));
            return;
        }
        for (FunctionDefinition function : functions.values()) {
            if (!used.contains(function.signature)) {
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
