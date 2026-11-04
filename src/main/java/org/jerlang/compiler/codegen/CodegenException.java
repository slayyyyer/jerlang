package org.jerlang.compiler.codegen;

/**
 * A source form that codegen understands structurally but can not lower yet.
 */
public class CodegenException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public CodegenException(String message) {
        super(message);
    }

}
