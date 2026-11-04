package org.jerlang.erts.erlang;

import java.util.ArrayList;

import org.jerlang.FunctionSignature;
import org.jerlang.Module;
import org.jerlang.ModuleRegistry;
import org.jerlang.stdlib.beam_lib.BeamData;
import org.jerlang.stdlib.beam_lib.LocalFunctionTableChunk;
import org.jerlang.type.Atom;
import org.jerlang.type.Binary;
import org.jerlang.type.Boolean;
import org.jerlang.type.List;
import org.jerlang.type.Term;
import org.jerlang.type.Tuple;

/**
 * The BIF behind {@code module_info/0,1}.
 *
 * The generated module_info functions call this with one item, or with no
 * item for the zero-arity form. The answers are read from the same tables the
 * loader uses, so a Java module and a BEAM module describe themselves the same
 * way when both have those tables.
 */
public class ErlangGetModuleInfo {

    private static final Atom MODULE = Atom.of("module");
    private static final Atom EXPORTS = Atom.of("exports");
    private static final Atom FUNCTIONS = Atom.of("functions");
    private static final Atom ATTRIBUTES = Atom.of("attributes");
    private static final Atom COMPILE = Atom.of("compile");
    private static final Atom MD5 = Atom.of("md5");
    private static final Atom NATIVE = Atom.of("native");
    private static final Atom NATIVE_ADDRESSES = Atom.of("native_addresses");

    private ErlangGetModuleInfo() {
    }

    public static Term dispatch(List params) {
        switch (params.length()) {
        case 1:
            return module_info(params.head().toAtom());
        case 2:
            Atom module = params.head().toAtom();
            params = params.tail();
            Atom item = params.head().toAtom();
            return get_module_info_2(module, item);
        default:
            throw Error.badarg;
        }
    }

    public static Term module_info(Atom module) {
        return List.of(
            Tuple.of(MODULE, module),
            Tuple.of(EXPORTS, exports(module)),
            Tuple.of(ATTRIBUTES, attributes(module)),
            Tuple.of(COMPILE, compile(module)),
            Tuple.of(MD5, md5(module)));
    }

    public static Term get_module_info_2(Atom module, Atom item) {
        if (item.equals(MODULE)) {
            return module;
        }
        if (item.equals(EXPORTS)) {
            return exports(module);
        }
        if (item.equals(FUNCTIONS)) {
            return functions(module);
        }
        if (item.equals(ATTRIBUTES)) {
            return attributes(module);
        }
        if (item.equals(COMPILE)) {
            return compile(module);
        }
        if (item.equals(MD5)) {
            return md5(module);
        }
        if (item.equals(NATIVE)) {
            return Boolean.am_false;
        }
        if (item.equals(NATIVE_ADDRESSES)) {
            return List.nil;
        }
        throw Error.badarg;
    }

    private static BeamData beamData(Atom module) {
        Module loaded = ModuleRegistry.get(module);
        return loaded == null ? null : loaded.beamData();
    }

    private static Term exports(Atom module) {
        BeamData data = beamData(module);
        if (data == null || data.exportTableChunk() == null) {
            return List.nil;
        }
        ArrayList<Term> terms = new ArrayList<>();
        for (FunctionSignature signature : data.exportTableChunk().exports()) {
            terms.add(Tuple.of(signature.function(), signature.fun_arity()));
        }
        return list(terms);
    }

    /**
     * All functions with a name and arity, in the order module_info reports
     * them: exported functions first, then the local functions.
     */
    private static Term functions(Atom module) {
        BeamData data = beamData(module);
        if (data == null) {
            return List.nil;
        }
        ArrayList<Term> terms = new ArrayList<>();
        if (data.exportTableChunk() != null) {
            for (FunctionSignature signature : data.exportTableChunk().exports()) {
                terms.add(Tuple.of(signature.function(), signature.fun_arity()));
            }
        }
        LocalFunctionTableChunk locals = data.localFunctionTableChunk();
        if (locals != null) {
            for (FunctionSignature signature : locals.records()) {
                terms.add(Tuple.of(signature.function(), signature.fun_arity()));
            }
        }
        return list(terms);
    }

    private static Term attributes(Atom module) {
        BeamData data = beamData(module);
        if (data == null || data.attributeChunk() == null) {
            return List.nil;
        }
        return data.attributeChunk().attributes();
    }

    private static Term compile(Atom module) {
        BeamData data = beamData(module);
        if (data == null || data.compileInfoChunk() == null) {
            return List.nil;
        }
        return data.compileInfoChunk().compile_info();
    }

    private static Term md5(Atom module) {
        Module loaded = ModuleRegistry.get(module);
        return loaded == null ? new Binary(new byte[16]) : new Binary(loaded.md5());
    }

    /**
     * List.of(Collection) prepends, which would reverse a table. module_info
     * preserves the table order, so build the list from the end.
     */
    private static List list(ArrayList<Term> terms) {
        List result = List.nil;
        for (int index = terms.size() - 1; index >= 0; index--) {
            result = new List(terms.get(index), result);
        }
        return result;
    }

}
