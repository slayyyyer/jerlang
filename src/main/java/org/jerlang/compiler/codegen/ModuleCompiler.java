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
import org.jerlang.stdlib.beam_lib.LineRecord;
import org.jerlang.stdlib.beam_lib.LineTableChunk;
import org.jerlang.stdlib.beam_lib.LineTableChunkWriter;
import org.jerlang.stdlib.beam_lib.LiteralTableChunkWriter;
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
    private final LinkedHashMap<Atom, RecordDef> records = new LinkedHashMap<>();
    private final java.util.List<Term> literals = new ArrayList<>();
    private final java.util.List<LineRecord> lineRecords = new ArrayList<>();
    private final java.util.Map<String, java.lang.Integer> lineIndexes = new HashMap<>();
    private int lineInstructions;
    private Atom module;
    private String source = "generated";
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
        if (!lineRecords.isEmpty()) {
            chunks.add(BeamFile.ChunkData.of("Line", lineChunk()));
        }
        chunks.add(BeamFile.ChunkData.of("StrT", new byte[0]));
        if (!literals.isEmpty()) {
            chunks.add(BeamFile.ChunkData.of("LitT", LiteralTableChunkWriter.write(literals)));
        }
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

    int literal(Term term) {
        for (int index = 0; index < literals.size(); index++) {
            if (literals.get(index).equals(term)) {
                return index;
            }
        }
        literals.add(term);
        return literals.size() - 1;
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

    RecordDef recordDefinition(Atom name) {
        return records.get(name);
    }

    int recordFieldIndex(Atom record, Atom field) {
        RecordDef definition = records.get(record);
        if (definition == null) {
            throw new CodegenException("unknown record " + record);
        }
        for (int index = 0; index < definition.fields.size(); index++) {
            if (definition.fields.get(index).equals(field)) {
                return index + 1;
            }
        }
        throw new CodegenException("record " + record + " has no field " + field);
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

    String source() {
        return source;
    }

    /**
     * Return the one-based index of a source location in the Line chunk. OTP
     * line instructions address this table, not a literal term.
     */
    int line(String filename, int line) {
        lineInstructions++;
        String key = filename + "\u0000" + line;
        java.lang.Integer existing = lineIndexes.get(key);
        if (existing != null) {
            return existing;
        }
        int index = lineRecords.size() + 1;
        lineRecords.add(new LineRecord(Atom.of(filename), line, 1, lineRecords.isEmpty()));
        lineIndexes.put(key, index);
        return index;
    }

    private byte[] lineChunk() {
        LineTableChunk chunk = new LineTableChunk(new Chunk(ChunkId.LINE, 0, 0),
            0, 0, lineInstructions, lineRecords.size(), 1);
        for (int index = 0; index < lineRecords.size(); index++) {
            chunk.records()[index] = lineRecords.get(index);
        }
        chunk.fnames()[0] = new Str(source);
        return LineTableChunkWriter.write(chunk);
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
            moduleInfo();
        }
        for (FunctionDef function : functions.values()) {
            atom(function.name);
        }
    }

    /**
     * Every module OTP compiles exports {@code module_info/0,1}, and the code
     * server calls them while loading an application. They are ordinary
     * functions here, not a special loader path, so both OTP and JErlang see
     * the same export table.
     */
    private void moduleInfo() {
        FunctionKey zero = new FunctionKey(Atom.of("module_info"), 0);
        FunctionKey one = new FunctionKey(Atom.of("module_info"), 1);
        if (functions.containsKey(zero) || functions.containsKey(one)) {
            return;
        }

        Term moduleAtom = astAtom(module);
        Term remote = Tuple.of(Atom.of("remote"), Integer.of(1), astAtom(Atom.of("erlang")),
            astAtom(Atom.of("get_module_info")));
        Term zeroCall = Tuple.of(Atom.of("call"), Integer.of(1), remote,
            List.of(moduleAtom));
        Term zeroClause = Tuple.of(Atom.of("clause"), Integer.of(1), List.nil,
            List.nil, List.of(zeroCall));
        FunctionDef zeroFunction = new FunctionDef(zero, List.of(zeroClause));
        functions.put(zero, zeroFunction);
        exports.put(zero, Boolean.TRUE);

        Term item = Tuple.of(Atom.of("var"), Integer.of(1), Atom.of("Item"));
        Term oneCall = Tuple.of(Atom.of("call"), Integer.of(1), remote,
            List.of(moduleAtom, item));
        Term oneClause = Tuple.of(Atom.of("clause"), Integer.of(1),
            List.of(item), List.nil, List.of(oneCall));
        FunctionDef oneFunction = new FunctionDef(one, List.of(oneClause));
        functions.put(one, oneFunction);
        exports.put(one, Boolean.TRUE);
    }

    private static Term astAtom(Atom atom) {
        return Tuple.of(Atom.of("atom"), Integer.of(1), atom);
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
        } else if (name.equals(Atom.of("file"))) {
            Term value = tuple.element(4);
            if (value instanceof Tuple && value.toTuple().arity() >= 1
                && value.toTuple().element(1) instanceof Str) {
                source = value.toTuple().element(1).toStr().string();
            }
        } else if (name.equals(Atom.of("record"))) {
            Tuple declaration = tuple.element(4).toTuple();
            Atom recordName = declaration.element(1).toAtom();
            List cursor = declaration.element(2).toList();
            java.util.List<Atom> names = new ArrayList<>();
            java.util.List<Term> defaults = new ArrayList<>();
            while (cursor != List.nil) {
                Term field = cursor.head();
                if (field instanceof Tuple && is(field.toTuple(), "typed_record_field")) {
                    field = field.toTuple().element(2);
                }
                Tuple definition = field.toTuple();
                Atom fieldName = definition.element(3).toTuple().element(3).toAtom();
                names.add(fieldName);
                defaults.add(definition.arity() >= 4 ? definition.element(4)
                    : astAtom(Atom.of("undefined")));
                atom(fieldName);
                cursor = cursor.tail();
            }
            atom(recordName);
            records.put(recordName, new RecordDef(recordName, names, defaults));
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

    private static int firstLine(List clauses) {
        if (clauses == List.nil) {
            return 1;
        }
        return clauses.head().toTuple().element(2).toInteger().toInt();
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
            Tuple.of(Atom.of("source"), new Str(source)),
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
        final int line;
        Atom selfName;

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
            this.line = firstLine(clauses);
        }
    }

    static final class RecordDef {

        final Atom name;
        final java.util.List<Atom> fields;
        final java.util.List<Term> defaults;

        private RecordDef(Atom name, java.util.List<Atom> fields,
            java.util.List<Term> defaults) {
            this.name = name;
            this.fields = fields;
            this.defaults = defaults;
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
