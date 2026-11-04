package org.jerlang.compiler.codegen;

import java.util.List;

import org.jerlang.type.Term;

/**
 * = Compiling abstract forms
 *
 * The input is the same erl_parse term stream the parser and linter use. The
 * output is a BEAM file, not an internal tree: the OTP loader and the existing
 * interpreter are the two independent consumers.
 *
 * This first A5 slice lowers functions, clauses, literal patterns, guards,
 * arithmetic BIFs, local calls and tuples. Unsupported forms fail with a
 * {@link CodegenException} rather than producing a module with a missing
 * branch.
 */
public final class Codegen {

    private Codegen() {
    }

    public static byte[] compile(List<Term> forms) {
        return new ModuleCompiler(forms).compile();
    }

}
