package org.jerlang.compiler.codegen;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

import org.jerlang.FunctionSignature;
import org.jerlang.Opcode;
import org.jerlang.erts.emulator.Instruction;
import org.jerlang.erts.etf.ExternalTermWriter;
import org.jerlang.stdlib.beam_lib.AtomChunk;
import org.jerlang.stdlib.beam_lib.AtomChunkWriter;
import org.jerlang.stdlib.beam_lib.BeamBytes;
import org.jerlang.stdlib.beam_lib.BeamFile;
import org.jerlang.stdlib.beam_lib.Chunk;
import org.jerlang.stdlib.beam_lib.ChunkId;
import org.jerlang.stdlib.beam_lib.CodeChunkAssembler;
import org.jerlang.stdlib.beam_lib.ExportTableChunk;
import org.jerlang.stdlib.beam_lib.FunctionTableChunk;
import org.jerlang.stdlib.beam_lib.FunctionTableChunkWriter;
import org.jerlang.stdlib.beam_lib.ImportTableChunk;
import org.jerlang.stdlib.beam_lib.LambdaInfo;
import org.jerlang.stdlib.beam_lib.LocalFunctionTableChunk;
import org.jerlang.stdlib.beam_lib.TableChunkWriter;
import org.jerlang.type.Atom;
import org.jerlang.type.Integer;
import org.jerlang.type.List;
import org.jerlang.type.Str;
import org.jerlang.type.Term;
import org.jerlang.type.Tuple;

/**
 * Turns one module's forms into a BEAM container.
 *
 * Labels are allocated before functions are lowered so a local call can name a
 * function that appears later in the source. Registers are kept in Y for values
 * that outlive a call; every operation's result is moved to a fresh Y slot, and
 * X registers are used only for arguments and BIF results.
 */
final class ModuleCompiler {

    private final java.util.List<Term> forms;
    private final LinkedHashMap<Atom, java.lang.Integer> atoms = new LinkedHashMap<>();
    private final LinkedHashMap<ImportKey, java.lang.Integer> imports = new LinkedHashMap<>();
    private final LinkedHashMap<FunctionKey, FunctionDef> functions = new LinkedHashMap<>();
    private final LinkedHashMap<FunctionKey, Boolean> exports = new LinkedHashMap<>();
    private final java.util.List<LambdaDef> lambdas = new ArrayList<>();
    private Atom module;
    private int nextLabel = 1;

    ModuleCompiler(java.util.List<Term> forms) {
        this.forms = forms;
        index();
    }

    byte[] compile() {
        if (module == null) {
            throw new CodegenException("module attribute is missing");
        }
        atom(module);
        for (FunctionDef function : functions.values()) {
            function.entry = label();
            function.body = label();
            function.clauseLabels.add(function.body);
            for (int clause = 1; clause < function.clauses.length(); clause++) {
                function.clauseLabels.add(label());
            }
        }

        java.util.List<Instruction> instructions = new ArrayList<>();
        for (FunctionDef function : functions.values()) {
            new FunctionCompiler(this, function, instructions).compile();
        }
        // Compiling a lambda can discover a nested lambda, so this loop must
        // not snapshot the list size.
        for (int index = 0; index < lambdas.size(); index++) {
            new FunctionCompiler(this, lambdas.get(index).function, instructions).compile();
        }
        instructions.add(new Instruction(instructions.size(), Opcode.int_code_end));

        AtomChunk atoms = atomChunk();
        java.util.List<BeamFile.ChunkData> chunks = new ArrayList<>();
        chunks.add(BeamFile.ChunkData.of("AtU8", AtomChunkWriter.write(atoms, true)));
        chunks.add(BeamFile.ChunkData.of("Code",
            CodeChunkAssembler.write(instructions, atoms, nextLabel,
                functions.size() + lambdas.size())));
        chunks.add(BeamFile.ChunkData.of("StrT", new byte[0]));
        chunks.add(BeamFile.ChunkData.of("FunT", functionTable(atoms)));
        chunks.add(BeamFile.ChunkData.of("ImpT", importTable(atoms)));
        chunks.add(BeamFile.ChunkData.of("ExpT", exportTable(atoms)));
        chunks.add(BeamFile.ChunkData.of("LocT", localTable(atoms)));
        chunks.add(BeamFile.ChunkData.of("Attr", termChunk(attributes())));
        chunks.add(BeamFile.ChunkData.of("CInf", termChunk(compileInfo())));
        return BeamFile.of(chunks).toBytes();
    }

    Atom atom(Atom atom) {
        atoms.putIfAbsent(atom, atoms.size() + 1);
        return atom;
    }

    int importIndex(Atom function, int arity) {
        return importIndex(Atom.of("erlang"), function, arity);
    }

    int importIndex(Atom module, Atom function, int arity) {
        ImportKey key = new ImportKey(module, function, arity);
        java.lang.Integer index = imports.get(key);
        if (index == null) {
            index = imports.size();
            imports.put(key, index);
            atom(key.module);
            atom(key.function);
        }
        return index;
    }

    FunctionDef function(Atom name, int arity) {
        FunctionDef function = functionOrNull(name, arity);
        if (function == null) {
            throw new CodegenException("undefined function " + name + "/" + arity);
        }
        return function;
    }

    FunctionDef functionOrNull(Atom name, int arity) {
        return functions.get(new FunctionKey(name, arity));
    }

    LambdaDef addLambda(Atom ownerName, int ownerArity, List clauses, List freeNames,
        int funArity) {
        int index = lambdas.size();
        Atom name = Atom.of("-" + ownerName + "/" + ownerArity + "-fun-" + index + "-");
        atom(name);
        FunctionKey key = new FunctionKey(name, funArity + freeNames.length());
        FunctionDef function = new FunctionDef(key, clauses, funArity, freeNames);
        function.entry = label();
        function.body = label();
        function.clauseLabels.add(function.body);
        for (int clause = 1; clause < clauses.length(); clause++) {
            function.clauseLabels.add(label());
        }
        LambdaDef lambda = new LambdaDef(function, index, freeNames);
        lambdas.add(lambda);
        return lambda;
    }

    int label() {
        return nextLabel++;
    }

    Atom module() {
        return module;
    }

    private void index() {
        for (Term form : forms) {
            if (!(form instanceof Tuple)) {
                continue;
            }
            Tuple tuple = form.toTuple();
            if (tuple.arity() < 4 || !is(tuple, "attribute") && !is(tuple, "function")) {
                continue;
            }
            if (is(tuple, "attribute")) {
                attribute(tuple);
            } else {
                function(tuple);
            }
        }
        if (module != null) {
            atom(module);
        }
        for (FunctionDef function : functions.values()) {
            atom(function.name);
        }
    }

    private void attribute(Tuple tuple) {
        Atom name = tuple.element(3).toAtom();
        if (name.equals(Atom.of("module"))) {
            Term value = tuple.element(4);
            module = value instanceof Tuple ? value.toTuple().element(1).toAtom() : value.toAtom();
        } else if (name.equals(Atom.of("export"))) {
            List list = tuple.element(4).toList();
            while (list != List.nil) {
                Tuple signature = list.head().toTuple();
                FunctionKey key = new FunctionKey(signature.element(1).toAtom(),
                    signature.element(2).toInteger().toInt());
                exports.put(key, Boolean.TRUE);
                atom(key.name);
                list = list.tail();
            }
        }
    }

    private void function(Tuple tuple) {
        Atom name = tuple.element(3).toAtom();
        int arity = tuple.element(4).toInteger().toInt();
        FunctionKey key = new FunctionKey(name, arity);
        if (functions.containsKey(key)) {
            throw new CodegenException("duplicate function " + name + "/" + arity);
        }
        functions.put(key, new FunctionDef(key, tuple.element(5).toList()));
    }

    private AtomChunk atomChunk() {
        AtomChunk chunk = new AtomChunk(new Chunk(ChunkId.ATU8, 0, 0), atoms.size());
        int index = 0;
        chunk.set(index++, module);
        for (Atom atom : atoms.keySet()) {
            if (!atom.equals(module)) {
                chunk.set(index++, atom);
            }
        }
        return chunk;
    }

    private byte[] exportTable(AtomChunk atoms) {
        ExportTableChunk table = new ExportTableChunk(new Chunk(ChunkId.EXPT, 0, 0));
        for (FunctionKey key : exports.keySet()) {
            FunctionDef function = functions.get(key);
            if (function == null) {
                throw new CodegenException("exported function is missing: " + key);
            }
            table.add(new FunctionSignature(module, key.name, Integer.of(key.arity),
                Integer.of(function.body)));
        }
        return TableChunkWriter.exports(table, atoms);
    }

    private byte[] importTable(AtomChunk atoms) {
        ImportTableChunk table = new ImportTableChunk(new Chunk(ChunkId.IMPT, 0, 0));
        for (ImportKey key : imports.keySet()) {
            table.add(new FunctionSignature(key.module, key.function, Integer.of(key.arity)));
        }
        return TableChunkWriter.imports(table, atoms);
    }

    private byte[] localTable(AtomChunk atoms) {
        LocalFunctionTableChunk table = new LocalFunctionTableChunk(new Chunk(ChunkId.LOCT, 0, 0));
        for (FunctionDef function : functions.values()) {
            if (!exports.containsKey(function.key)) {
                table.add(new FunctionSignature(module, function.name, Integer.of(function.arity),
                    Integer.of(function.body)));
            }
        }
        return TableChunkWriter.locals(table, atoms);
    }

    private byte[] functionTable(AtomChunk atoms) {
        FunctionTableChunk table = new FunctionTableChunk(new Chunk(ChunkId.FUNT, 0, 0));
        for (LambdaDef lambda : lambdas) {
            table.add(new LambdaInfo(atomIndex(atoms, lambda.function.name),
                lambda.function.arity, lambda.function.body, lambda.index,
                lambda.freeNames.length(), 0, lambda.index, atoms));
        }
        return FunctionTableChunkWriter.write(table, atoms);
    }

    private static int atomIndex(AtomChunk atoms, Atom atom) {
        Atom[] table = atoms.atoms();
        for (int index = 0; index < table.length; index++) {
            if (atom.equals(table[index])) {
                return index + 1;
            }
        }
        throw new CodegenException("lambda function atom is missing: " + atom);
    }

    private Term attributes() {
        List exportsList = List.nil;
        for (FunctionKey key : exports.keySet()) {
            exportsList = new List(Tuple.of(key.name, Integer.of(key.arity)), exportsList);
        }
        return List.of(Tuple.of(Atom.of("module"), module),
            Tuple.of(Atom.of("exports"), exportsList));
    }

    private Term compileInfo() {
        return List.of(Tuple.of(Atom.of("options"), List.nil),
            Tuple.of(Atom.of("source"), new Str("generated")),
            Tuple.of(Atom.of("version"), new Str("8.0")));
    }

    private static byte[] termChunk(Term term) {
        return ExternalTermWriter.encode(term).toByteArray();
    }

    private static boolean is(Tuple tuple, String name) {
        return tuple.arity() >= 1 && tuple.element(1).equals(Atom.of(name));
    }

    static final class FunctionDef {

        final FunctionKey key;
        final List clauses;
        final java.util.List<java.lang.Integer> clauseLabels = new ArrayList<>();
        int entry;
        int body;
        final Atom name;
        final int arity;
        final int patternArity;
        final List freeNames;

        private FunctionDef(FunctionKey key, List clauses) {
            this(key, clauses, key.arity, List.nil);
        }

        private FunctionDef(FunctionKey key, List clauses, int patternArity, List freeNames) {
            this.key = key;
            this.clauses = clauses;
            this.name = key.name;
            this.arity = key.arity;
            this.patternArity = patternArity;
            this.freeNames = freeNames;
        }
    }

    static final class LambdaDef {

        final FunctionDef function;
        final int index;
        final List freeNames;

        private LambdaDef(FunctionDef function, int index, List freeNames) {
            this.function = function;
            this.index = index;
            this.freeNames = freeNames;
        }
    }

    private static final class FunctionKey {

        private final Atom name;
        private final int arity;

        private FunctionKey(Atom name, int arity) {
            this.name = name;
            this.arity = arity;
        }

        @Override
        public boolean equals(Object object) {
            if (!(object instanceof FunctionKey)) {
                return false;
            }
            FunctionKey other = (FunctionKey) object;
            return name.equals(other.name) && arity == other.arity;
        }

        @Override
        public int hashCode() {
            return name.hashCode() * 31 + arity;
        }

        @Override
        public String toString() {
            return name + "/" + arity;
        }

    }

    private static final class ImportKey {

        private final Atom module;
        private final Atom function;
        private final int arity;

        private ImportKey(Atom module, Atom function, int arity) {
            this.module = module;
            this.function = function;
            this.arity = arity;
        }

        @Override
        public boolean equals(Object object) {
            if (!(object instanceof ImportKey)) {
                return false;
            }
            ImportKey other = (ImportKey) object;
            return module.equals(other.module) && function.equals(other.function)
                && arity == other.arity;
        }

        @Override
        public int hashCode() {
            return (module.hashCode() * 31 + function.hashCode()) * 31 + arity;
        }

    }

}
