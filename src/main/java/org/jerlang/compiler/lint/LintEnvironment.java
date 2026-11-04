package org.jerlang.compiler.lint;

import org.jerlang.type.Atom;

/**
 * = What the scope analyser may ask the module
 *
 * The analyser owns variable flow; the linter owns the function table and the
 * final diagnostic list. Keeping the boundary this small means the expression
 * rules can be tested without constructing a full module index.
 */
interface LintEnvironment {

    void error(int line, String message);

    void warning(int line, String message);

    /**
     * A local call or fun reference. The module decides whether the target is
     * defined, imported or an auto-imported BIF, and records a defined target
     * as used.
     */
    void localUse(int line, Atom name, int arity, boolean reference);

    boolean warnUnusedVariables();

    boolean warnShadowVariables();

}
