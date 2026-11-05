# Compiling Erlang source to BEAM

1.x reads the `.beam` files that `erlc` produces. 2.0 is the version where
JErlang also produces them: an Erlang source compiler that emits standard OTP
29 `.beam` files, so a JVM-only deployment does not need an Erlang installation
to build its own modules.

This document is the plan for that work. It is written to be executed by
agents, so it records the decisions that would otherwise be re-litigated in
every session, and it defines done as an executable check rather than a
judgement. It records the completed compiler track that shipped in 2.0.0.

## Two things called a compiler

They are not the same project and only one is in scope:

* **Source to BEAM** — the subject of this document. JErlang becomes a
  toolchain, and the interpreter gains a second, independent implementation of
  the format it already parses.
* **BEAM to JVM bytecode** — a JIT. This was the original roadmap's "0.5
  Compiler", deferred because nothing measured the interpreter as too slow and
  because it freezes semantics work. It is still deferred; it is performance
  work, not compatibility work, and it is tracked separately.

## Definition of done

"BEAM compatible" here means exactly these, in this order. Anything weaker is a
disassembler, not a compiler.

1. `beam_lib:chunks/2` on OTP 29 accepts the file.
2. `code:load_binary/3` on a real OTP 29 node loads it without an error.
3. A module built by JErlang behaves like the same module built by `erlc`:
   same exports, same return values, same `try`/`catch` classes and reasons.
4. Stacktraces name the same file, line, function and arity.
5. `beam_lib:md5/1` of the file agrees with the digest the compiler records for
   its own funs, so a `fun` created in a JErlang-compiled module survives a
   round trip through distribution and `term_to_binary`.
6. JErlang itself loads and runs the file. The interpreter is the second
   consumer and a regression net, never the judge of correctness.

**Byte-identical output is not a goal.** Matching `erlc` byte for byte would
mean reimplementing `beam_ssa_opt` exactly, for no semantic gain. Semantic
equivalence plus OTP acceptance is the bar, and any deviation from it is a bug.

## What already exists

The output half of a compiler is largely specified by code that reads the
format, which is why the first milestone is cheap.

| Component | Where | What it gives the compiler |
|---|---|---|
| Opcode table | `src/main/java/org/jerlang/Opcode.java` | All 191 numbers from `genop.tab` with declared arity, which is the table the emitter indexes. |
| Operand codec, decode direction | `AbstractReader.decodeArg`, `InstructionReader` | The packed small/11-bit/27-bit integer forms, `a` as a 1-based atom index with 0 meaning `nil`, and the `z` extended forms (inline list, float register, allocation list, literal index, typed register, float). The encoder is its inverse. |
| Chunk readers | `src/main/java/org/jerlang/stdlib/beam_lib/*ChunkReader.java` | One reader per chunk: `Atom`/`AtU8` (including OTP 28's negative count and compact lengths), `Attr`, `CInf`, `Code`, `ExpT`, `FunT`, `ImpT`, `Line`, `LitT`, `LocT`, `StrT`, `Abst`. Each one is the writer's specification. |
| Term encoding | `ExternalTermWriter` | Literal table terms, and the `Abst` chunk, which is term-encoded abstract forms. |
| Fun digest | `BeamLibMD5`, `ChunkId.sortOrder` | The `beam_lib:md5/1` digest and the chunk ordering it depends on. Emitting a wrong digest makes every fun in the module fail with `badfun` on a peer. |
| Loader and model | `ModuleLoader.loadBeamData`, `BeamData`, `Module` | A read path from file to instructions that the assembler and the disassembler both build on. |
| Conformance harness | `ConformanceReport`, `PassRateTest`, `src/test/erl/corpus` | The classification and skip-list pattern the compiler's harness should copy. |

## What does not exist

* **No writer of any kind.** There is no `*Writer` in `beam_lib`, no IFF
  container writer, and no instruction encoder. This is the whole of milestone
  A1.
* **No carrier for unknown chunks.** `BeamLibInfo` drops every chunk whose
  `ChunkId.skip()` is true (`Dbgi`, `Meta`, `Type`, the HiPE chunks), and
  `BeamData` has no field for them. A round trip of any real OTP file will
  therefore lose bytes and the md5 will not match until `BeamData` (or a
  writer-only carrier) holds those chunks as opaque payloads. Do this first;
  the exit test finds it immediately otherwise.
* **No front end.** There is no scanner, preprocessor, parser, abstract format,
  or linter: a search for `Lexer|Parser|Scanner|Ast|ErlParser` finds nothing.
  This is milestones A3 and A4, and most of the work.
* **No command.** There is no `jerlc`, no `JerlangCompiler` entry point and no
  `-o`/`-I`/`-D` option handling.

## Pipeline and where the code goes

```
.erl source
   |  scan          org.jerlang.compiler.scan
   |  preprocess    org.jerlang.compiler.preprocess   (-define, -include, -ifdef)
   v  parse         org.jerlang.compiler.parse        -> erl_parse abstract forms
 forms
   |  lint          org.jerlang.compiler.lint         -> errors, warnings
   |  codegen       org.jerlang.compiler.codegen      -> instructions + tables
   v  emit          org.jerlang.stdlib.beam_lib       -> .beam bytes
.beam
```

Format code stays in `org.jerlang.stdlib.beam_lib`, next to the readers it
mirrors, so a reader and its writer are always edited together. Compiler logic
lives in the new `org.jerlang.compiler` package. That package is public API, so
it lands on 2.0.0 and not before.

The abstract format is the internal IR, not a hand-rolled node hierarchy: it is
documented, stable, what OTP's own back end consumes, and it makes "is my
parser right" a term-by-term diff against `erl_parse:parse_form/1` instead of a
judgement call.

Core Erlang is skipped in the first cut. Abstract forms go to instructions
directly. That is defensible because the interpreter does not care how the
module was optimized, and it avoids implementing a full intermediate language
before anything runs. Revisit only if a correctness problem turns out to need
it.

## Milestones

Each milestone has one exit test, and it is done when that test runs in CI. The
existing roadmap's discipline applies unchanged.

### A1 — Assembler: read a `.beam`, write it back

Scope: IFF container (`FOR1`, length, `BEAM`, 4-byte-aligned chunks with the
`ChunkId.sortOrder` order), one writer per chunk, the inverse operand encoder,
opaque pass-through for skipped chunks, and the fun digest.

**Exit test:** for every module in a checked-in corpus of files built by OTP
29.1.2, the re-emitted file has the same `beam_lib:md5/1`, and a real OTP node
loads and runs it. Corpus targets the 1366 modules of an OTP installation,
filtered only by "the reader can load it today".

This is the highest-value milestone in the plan: it forces the container, atom
table, literal table, line table, lambda table and operand encoding to be right,
needs no parser, and its oracle is a single hash comparison that cannot be
argued with.

**Submilestones.** Each one is a session-sized item and leaves the previous
tests green.

| # | Item | Exit |
|---|------|------|
| A1.1 | Container: read every chunk in file order, including the ones the loader skips (`Dbgi`, `Meta`, `Type`, HiPE), and write `FOR1`/`BEAM` back with the length and 4-byte padding recomputed | Every checked-in `.beam` re-emits byte for byte |
| A1.2 | Operand codec: the encoder for the compact integer forms, `a`, `x`, `y`, `f` and the `z` extended tags | A property test that re-encodes every operand of every instruction in the corpus to the bytes it came from |
| A1.3 | `Atom` and `AtU8`, including OTP 28's negative count and compact lengths | Byte identity with the atom chunk re-encoded from its atoms |
| A1.4 | `ExpT`, `ImpT`, `LocT`, `StrT` | Byte identity for the four table chunks |
| A1.5 | Term chunks: `LitT` uncompressed, `Attr`, `CInf`, `Abst` through `ExternalTermWriter` | Byte identity, or a written reason a term cannot round trip |
| A1.6 | `FunT` lambda table, including `OldUniq` | Byte identity, and `beam_lib:md5/1` unchanged with `FunT` re-encoded |
| A1.7 | `Line`: filename table and the `a`/`i` items | Byte identity for modules with line information |
| A1.8 | `Code`: header, instruction stream, and the operand tags and widths the decoder currently discards | `beam_lib:md5/1` identity for the whole corpus with `Code` re-encoded |
| A1.9 | Corpus and CI gate | A job that asserts md5 identity over the checked-in fixtures and loads the re-emitted files on OTP 29. `BeamAssemblerTest` is the digest gate; `OtpLoadTest` runs every fixture through `code:load_binary/3` and skips itself when no Erlang is installed |

Status: A1 is done. The four A1 tests re-emit all 104 fixtures with the same `beam_lib:md5/1`, re-encode every operand in the corpus, and write the atoms, the tables, `FunT`, `Line` and `Code` back byte for byte. `LitT`, `Attr`, `CInf` and `Abst` are carried unchanged; the reasons are under "What A1 found". The OTP 29 load check is described below. A2 followed.

A1.1 and A1.8 were the two that carried risk, and both landed as expected. A1.1 because `Meta` is part of the digest, so a writer that drops the chunks the loader ignores produces a different module identity; A1.8 because `AbstractReader.decodeArg` returned a `Term` and not the bytes it read, so the original tag and width of an operand did not survive decoding. A1.2 answered that question: the decoder has to keep the encoding. Two operand kinds lose more than the width, which no canonical encoder can recover: a literal operand decodes to the literal itself, so its index into the literal table is gone, and a typed register decodes to the register, so its type is gone.

**The load check.** `OtpLoadTest` writes every assembled fixture to a temporary directory and asks `code:load_binary/3` to accept it, with the original file as the control: a rewrite is only a defect if OTP takes the original and refuses the rewrite, and a refusal has to carry the same reason. On OTP 29 (erts 17.1.1) 47 of the 104 fixtures load, one is skipped because it is the OTP `lists` module, and 56 are refused by both versions with `badfile`: the corpus reaches back to releases whose instructions this validator no longer accepts, and several of those files have no `CInf` chunk at all. Nothing that loads before the rewrite fails after it. The test finds Erlang through `ERL`, `ERLANG_HOME` or `PATH` and skips itself when there is none, so `mvn test` stays green on a machine without OTP.

### What A1 found

Four things about the format are not visible in the loader, and all four were found by comparing the writers against the corpus:

* The atom table has no terminator. The reader used to read one byte past the last atom and never failed, because the loader gives each reader a stream over the whole file, so the byte it took was the next chunk id. A writer that did not know this grew the chunk by one byte.
* `Attr`, `CInf` and `Abst` cannot be re-encoded yet. The compiler writes atoms as `ATOM_EXT` with a two byte length; `ExternalTermWriter` writes `SMALL_ATOM_UTF8_EXT` with a one byte length. A 40 byte `Attr` chunk came back as 39.
* A map keeps its pair order on the wire and `Map` does not. A literal `#{a := 10, b := 2}` came back as `#{b := 2, a := 10}`: the same term, different bytes. This is what blocks `LitT` even where it is uncompressed, and it is a decision for A5: the compiler needs a map that keeps insertion order, or literal order has to be chosen and never re-derived.
* `LocalFunctionTableChunk.locals()` returns its records reversed, because `List.of` prepends. That is harmless to the loader and fatal to a writer; `records()` returns file order.

The lesson is the shape of the milestone rather than the four bugs: a writer is only correct when it is compared with a file it did not produce. Every one of these would have passed a test that only loads the module.

### A2 — Disassembler

Scope: `.beam` to a textual assembly form and back. Purpose is a debuggable IR
and golden files, so a codegen bug shows up as a diff instead of a mystery.

**Exit test:** round trip to byte-identical output for the corpus, and every
disassembly is a stable checked-in fixture.

Status: done. `Disassembler` decodes the container, the atom table, `ExpT`, `ImpT`, `LocT`, `FunT`, `Line` and every instruction in `Code` with its operand encoding; `TextAssembler` reads all of it back. `LitT`, `Attr`, `CInf`, `Abst`, `StrT` and the chunks nobody interprets stay hex, and are copied, for the reasons A1 recorded. `DisassemblyRoundTripTest` proves all 104 fixtures survive the trip byte for byte, and all 104 listings are checked in under `src/test/resources/disassembly`.

### A3 — Front end: source to abstract forms

Scope, in this order: scanner; preprocessor; parser; `-file` and line
annotation parity.

Traps worth naming up front, because each one is a session of its own: OTP 27+
tokens (numeric separators, sigils, triple-quoted strings); macro arity,
`??Arg` stringification, `-include_lib` resolution and `-feature`; the
`{attribute,_,file,_}` and `{eof,_}` forms `epp` emits; ten operator precedence
levels; guards in every clause position; record access, update and nested
update; binary and map comprehensions; `maybe` and `?=`; named funs and
`fun M:F/A`.

**Exit test:** for every `.erl` under an OTP `lib` tree, the forms match
`erl_parse:parse_form/1` term by term, or the case is in a checked-in skip list
with an ID. The pass count is the metric and it is published, the way the
conformance pass rate already is.

Status: done. The scanner is in
`org.jerlang.compiler.scan`; `ScannerOtpTest` matches every token with
`erl_scan:string/3` on OTP 29: 110 files, no disagreement. The preprocessor is
in `org.jerlang.compiler.preprocess` and `PreprocessOtpTest` matches every
preprocessed form with `epp:scan_file/2`: 110 sources, 110 forms, 0 skipped.
The parser is in `org.jerlang.compiler.parse`; `ParserOtpTest` feeds the same
tokens to `erl_parse:parse_form/1` and compares the abstract forms: 110
sources, 110 form streams, 0 skipped. It covers the expression grammar,
attributes, records, binaries, comprehensions, `try`/`receive`/`fun`/`maybe`,
and the type and spec grammar.

The OTP `lib` exit test ran over 1404 `.erl` sources: 1019 form streams matched
`epp` and `erl_parse` term by term, none disagreed, and 385 are named skips in
`src/test/resources/otp/parse_skips.txt`. Those 385 are sources this installed
tree cannot preprocess: 373 need build-only headers, 5 need build-only macros,
and 7 need doc sources absent from the installation. Each has a stable hashed
ID. None is a parser gap. The preprocessor also reproduces epp's CRLF include
quirk: after an include, a CRLF parent keeps the include line, while an LF
parent reports the next token's line.

**What A3 found.** The preprocessor is small; the behaviour that is easy to
miss is not. A macro body is re-annotated with the line of the `?` that used
it, not the line it was written on, or every warning inside a macro points at
the header. Macro names are variables as well as atoms, so `?MODULE` is a
`var` token named `MODULE` and a table keyed only by atom finds nothing.
`??Arg` is the argument's tokens joined with one space, each written as it
appeared, quotes included. Finally, `filename:join/2` lowercases the drive
letter on Windows, so epp's include `-file` name starts `c:/` even when the
source path started `C:/`; matching it is part of the token stream, not
politeness.

### A4 — Lint: accept what `erlc` accepts

Scope: unsafe variables across clauses and branches, unbound variables in
patterns, illegal guard expressions, duplicate map keys, ambiguous binary
patterns, `-export` of a missing function, arity and redefinition conflicts,
and the warnings that make a build noisy in practice (unused, shadowed).

This is the step that is routinely underestimated: the bar is not "compiles",
it is "accepts and rejects exactly what `erlc` does". Every deviation gets a
documented line in this file, not a silent pass.

Status: complete. The module pass indexes definitions, exports, imports,
`on_load`, and the `nowarn_*`/`export_all`/`no_auto_import` options.
`FunctionScope` owns branch merging, unsafe-variable contexts, patterns,
guards, maps, binaries, comprehensions, named funs, `try`/`catch` and
`maybe`. It reports unbound and unsafe variables, undefined local calls and
fun references, illegal guards and patterns, ambiguous binaries, duplicate
map keys, and the unused, shadowed and export warnings. Obsolete OTP 29
type-test guards (`port/1`, `atom/1`, ...) are accepted with the same warning
`erlc` emits, so `-Werror` agrees.

`LinterOtpTest` compares 37 clean, error and warning cases against `erlc` in
both normal and `-Werror` modes. `LinterLibOtpTest` is the A4 exit test: over
the installed OTP 29 tree it walks 1,404 `.erl` files, applies the 385 named
A3 parse skips, and compares the remaining 1,019 verdicts with `erlc`.
Result on 2026-11-03: 1,019 accepted by both, 0 rejected, 0 disagreements.
The corpus test is opt-in because it needs an OTP installation and takes
about two minutes.

Known limits: the abstract format loses columns, so diagnostics are line
based. The corpus table compares accept/reject only; warning parity is
contract-tested by the 37-case table, not by comparing every `erlc` warning.

**Exit test:** a two-column table over the corpus (accepted, rejected) that
matches `erlc`'s verdict on every file, plus `-Werror` behaviour on the warning
cases.

### A5 — Codegen: abstract forms to instructions

Scope: an assembly IR, label allocation, X/Y register allocation from liveness,
guard chains and fail labels, `func_info` entry labels for `function_clause`,
`try`/`catch`/`try_case`/`raise`/`build_stacktrace`, one and several `receive`
blocks with the 28+ markers, binary matching as `bs_start_match3/4` plus a
`bs_match` command list, `bs_create_bin` with string segments as `StrT`
offsets, `make_fun3` with its lambda table and free-variable list, and line
tables.

The traps that produce wrong answers rather than crashes: a value live across a
call must be in a Y register or it is clobbered; a fun's `OldUniq` must be zero
before the digest is taken; a `string` segment is an offset, not inline bytes.

Status: complete. The first vertical slice emits a complete BEAM container
for functions, clauses, literal patterns (integer, atom, tuple, list, string,
map, record and `nil`), guard sequences and alternatives, arithmetic BIFs,
auto-imported BIFs, local and remote calls, tuple/list/string/map/record
construction, `case`/`if`, `try ... of ... catch` with or without `after`,
`receive` with selective clauses and an optional `after` timeout, anonymous,
named and remote funs (`fun M:F/A`), binary generators, short-circuit
`andalso`/`orelse`, and unary `+`/`-`. Dynamic remote calls, old-style `catch`,
`!`, list `++`/`--`, expression comparisons, alias and variable-key map
patterns, and the guards `not`, `is_map_key/2`, `is_record/2` and
`is_function/2` are emitted as well.
Remote calls go through `ImpT` and
`call_ext`; maps start from `maps:new/0` and update with `put_map_assoc`. Values
that survive a call are kept in Y registers; every operation result gets a
fresh Y slot, and X registers are used only for arguments and BIF results.
Labels are allocated before lowering so forward local calls resolve, and
case/if branches restore their pre-branch variable scope while joining
variables bound in every branch.

Two end-to-end tests consume the same file through independent paths.
`CodegenOtpTest` writes a module and asks OTP 29 to load it and call exported
functions: recursion, guards, tuple/list/string/map construction, list/string/
map patterns, case/if branches, guard alternatives, local and remote calls,
try/catch and try/of/catch error and throw paths, try-after cleanup on normal,
throw, error, catch-body and `try_clause` paths, receive with queued messages,
selective scanning, timed wakeups and zero timeouts, fun captures, arity zero,
multiple lambda clauses, nested lambdas and a fun passed into a compiled call,
binary construction and matching (including explicit sizes and used tails),
short-circuit booleans, unary arithmetic, and unmatched function/case/if
expressions (which must raise
`function_clause`, `case_clause` and `if_clause`) all agree with `erlc`
behaviour.
`CodegenInterpreterTest` loads that file through `ModuleLoader`, registers it,
and makes the same calls through JErlang. Both are green as of 2026-11-04.

Binary construction uses `bs_create_bin`; matching uses `bs_start_match3` and a
flat `bs_match` command list. Non-empty literal tables are written as OTP 28+
uncompressed `LitT`. A `binary` tail match keeps its context in X1 so `get_tail`
can write the tail over it, saves the position in X0, and extracts fixed fields
into X0 and X2+. Sizes in match commands are emitted as plain integers, while
`bs_create_bin` constants keep the signed `{integer,N}` operand tag OTP expects.
Tuple and list construction emit `test_heap` first; `test_heap` is a no-op in
JErlang, but OTP corrupts the heap without it. These changes are covered by
`bin_lit`, `bin_empty`, `bin_construct`, `bin_match`, `bin_tail`,
`bin_tail_used`, `bin_string` and `bin_size` in both consumer tests.

Line tables are emitted, and `erlang:get_module_info/2` exposes `module`,
`exports`, `functions`, `attributes`, `compile`, `md5`, `native` and
`native_addresses`. The checked-in corpus now recompiles and runs through both
`CodegenOtpTest`'s OTP oracle and `CodegenCorpusTest`, which loads the same
beams in JErlang and checks `run/0 =:= ok`. Named funs, `fun M:F/A`, record
construction/update/access/patterns, binary generators, arbitrary `=` patterns
and normalized catch stacktraces are covered by those paths. The A5 exit test,
`CodegenBootTest`, compiles `gen_server`, `supervisor`, `proc_lib`,
`application`, `logger` and `code` from the installed OTP 29 sources, puts those
beams ahead of the installed ones, boots the milestone 6 application, calls the
worker and stops the application again. It is green as of 2026-11-04. `StrT` is
still unused, `module_info(attributes)` does not yet match `erlc`'s `vsn` entry,
and expression-level line parity is coarser than `erlc`. Unsupported forms raise
`CodegenException`; they are not silently omitted.

Two format facts cost a debugging cycle and belong in the next agent's notes.
The module atom must be first in `AtU8`: OTP derives the module identity from
that position, even when `Attr` names the right module. Raw non-negative
integer operands are emitted with the unsigned `u` tag, not `i`; `func_info`'s
arity in particular is rejected by OTP when written as `i`. JErlang's
`is_nil` and `is_nonempty_list` originally read only X registers; generated
pattern code stores arguments in Y registers, so both now resolve operands
through the normal register reader. A third trap is `List.of(Collection)`:
the runtime helper prepends, so map update and `get_map_elements` operand
pairs must be reversed before encoding or the map is built with the wrong
keys. A `receive after 0` has no timer to wake it, so `waitForMessage` queues
the process itself when its timeout has already expired; otherwise the
scheduler sleeps forever. A receive clause's message arrives in `x(0)`, so it is
copied to a Y slot before the clause body is compiled: a body expression such
as a call can clobber the X registers, and a variable bound to the whole message
must still read the message afterwards. A lambda's FunT label is its body label, not its
`func_info` entry label, and its arity includes the captured variables. BEAM
calls a fun with the arguments first and the captured values after them, so the
lambda body binds its patterns to the first Y slots and its free names to the
slots after them. The free-variable operand of `make_fun3` is an operand list
even when it is empty; passing `nil` encodes an atom and OTP rejects the
instruction. Finally, the Code chunk's function count includes lambdas, not
just exported and local functions. Auto-imported BIF names such as `throw/1` and `error/1` must become
`call_ext` to `erlang`, not local calls; the code generator uses the same OTP
29 table as the linter. A fourth trap is the `f` operand of a `test`
instruction: it is the failure target, not the success target. Putting the
short-circuit result there inverted every `andalso`/`orelse`; the direct truth
table through OTP exposed it before the tests were accepted. A fifth is handler
allocation: reserve handler Y slots below result slots, initialize them at
function entry, and allocate them downward as `try` nesting is entered. OTP
requires the outer handler above the inner one; with the opposite order the
inner catch is bypassed, and without initialization a second call can restore a
stale handler.

**Exit test:** recompile the checked-in corpus with JErlang, run the existing
suite against the recompiled modules, and diff behaviour against the `erlc`
build. Then the strong form of the same test: the `otp` CI job compiles
`gen_server`, `supervisor`, `application`, `proc_lib`, `logger` and `code` with
JErlang and boots them.

### A6 — Command and surface

Scope: `jerlang compile` (or `jerlc`), `-o`, `-I`, `-pa`, `-D`, `-W`,
`+term` options, `.hrl` includes, a public `org.jerlang.compiler.Compiler`
entry point, and documentation in `getting-started.md`.

2.0.0 is the release that may break API, so this is also where `Instruction`,
`Opcode.arity()` and `ModuleLoader` get reshaped if the emitter wants them
different. After 2.0.0 the `japicmp` gate freezes them again.

Status: complete. `org.jerlang.compiler.Compiler` is the public entry point;
`CompilerCommand` is the `compile` subcommand of the runnable jar
(`java -jar jerlang-<version>-all.jar compile ...`). It accepts `-o`, `-I`, `-pa`, `-D`,
`-W`, `-W0`, `-Werror`, `+debug_info` and `+warnings_as_errors`. `-Dname`
expands to `true` and `-Dname=value` scans the value as Erlang tokens, so it
agrees with `erlc`. `-W` runs the A4 linter; lint errors stop the beam, and
`-Werror` makes warnings stop it too. Unknown options are errors, not silently
ignored flags. The command never invokes `erlc`.

**Exit test:** CI compiles the corpus with the shipped command and no `erlc` on
  the machine, then runs the suite. `CompilerCommandTest` is that test: it
  compiles `src/test/erl/corpus` through `CompilerCommand`, loads every beam in
  JErlang and runs `run/0`; a second case covers an `-include` header, an
  `-include_lib` application root, `-D` macros, `-W`, `-Werror`, `-o` and
  `+debug_info`.

### A7 — Parse transforms

Scope: run `-compile({parse_transform, Module}).` and entries in a
`-compile([...])` option list after parsing and before linting or code
generation, load the transform from `-pa` or the code path, execute it in
JErlang, and report a missing or malformed transform as a compile error.

Status: complete. A transform receives the parsed forms and an OTP-shaped
compiler option list containing `debug_info`, `warnings_as_errors`,
`{i, Dir}` and `{d, Name, Value}` entries. `-pa` roots are searched directly,
under `ebin`, and under versioned application directories; the working
directory and the ordinary code path are the fallbacks. The directory holding
the transform is put on the code path for the call, so helper modules beside it
load as well.

The compiler starts a VM only when a transform is present and shuts it down
again if it was the one that started it. A transform that has to spawn an
Erlang process is bounded by `parseTransformTimeoutMillis` (30 seconds by
default; zero disables the bound in the Java API). The timeout is a compile
error, not a warning, and no beam is written. This is not an OS sandbox:
trusted transform code can still call `erlang:halt/1` or leave native work
behind.

**Exit test:** `ParseTransformTest` compiles a transform module with JErlang,
compiles a subject through `jerlang compile -pa ...`, loads the resulting beam
and checks the rewritten function. It also covers ordered transforms, a
transform helper loaded from `-pa`, compiler options, timeout failure, and a
missing transform. `CompilerApiTest` covers failed `Result`s and preservation
of an existing beam after a failed compile.

## Timeline, re-estimated for agent execution

The human estimate for A1–A6 was 9–15 months. Agents change the shape of that,
not just its size, because the work splits cleanly into two kinds.

| Milestone | Human | Agent | Why the ratio differs |
|---|---|---|---|
| A1 assembler | 1–2 months | 2–3 weeks | Pure transcription: every writer has a reader next to it, and the oracle is one hash comparison. |
| A2 disassembler | ~1 month | 4–6 days | Little ambiguity, golden files, no semantics. |
| A3 front end | 4–5 months | 6–9 weeks | Large but mechanical, parallelizable, and `erl_parse` gives a term-exact oracle. `epp` is the long tail. |
| A4 lint | included above | 3–5 weeks | A large set of small independent rules, each with a `erlc` verdict to match. |
| A5 codegen | 6–9 months | 3–4 months | Judgment per failure: every bug is a semantics question and the oracle (does OTP boot it) is slow. |
| A6 command | 1–2 months | 2–3 weeks | Plumbing. |
| **Total** | **9–15 months** | **4.5–6 months** | |

In agent terms that is roughly 110–150 agent-days of active work, and 4.5–6
months of calendar time with one workstream. Two workstreams can overlap after
A1 lands — back end (A2, then the emitters) and front end (A3, A4) are
independent until A5 — which buys maybe three to four weeks of wall clock, not
half the schedule, because A5 is on the critical path and cannot be split far
without integration cost.

The line count does not explain the schedule. A1–A6 is roughly 25–35k lines,
which at a few thousand verified lines a day is two to three weeks of writing.
The other four and a half months are oracles: building them, waiting on them,
and reading their failures.

### What gets faster, and what does not

Faster than the human estimate, by a lot, when the oracle is exact and offline:

* Transcription of a format that another file in the same package already
  reads. A1 is the extreme case.
* Breadth with no coupling: 60 token kinds, 120 productions, 80 lint rules.
  These parallelize across sessions and do not need one author's mental model.
* Test scaffolding, fixtures, golden files, skip lists, CI wiring.
* The edit-compile-test loop itself, which is where most human time goes on
  mechanical code.

Roughly the human speed, or worse, where the work is judgement:

* Register allocation, guard chains, `try` and `receive` shapes. A plausible
  wrong answer passes a smoke test and fails two hundred modules later.
* `epp` corner cases where OTP's behaviour is arguably a bug and the plan has
  to say whether to match it. Agents pick the locally reasonable option unless
  the document decides first, which is what this document is for.
* Debugging across layers: a wrong atom index manifests three subsystems away.
* Review. Volume of generated code outruns what review absorbs, and the failure
  mode is silent divergence rather than a slow build.

### Rules that keep the estimate true

1. **Oracle first.** The first commit of every milestone is its harness, not its
   feature. A milestone with no oracle is a milestone with no schedule.
2. **Check in the fixtures.** The corpus of `erlc`-built `.beam` files lives in
   the repository, so the assembler oracle is offline, deterministic and runs
   in seconds. Anything that needs a live OTP install stays in the nightly
   `otp` job, not in the inner loop.
3. **Asymmetric oracles only.** Never validate a writer with its own reader, or
   a parser with its own printer. The oracle is an `erlc` build, a real OTP
   node, or OTP's own output, every time. A round trip through code the same
   session wrote proves nothing and is the most likely way this plan produces a
   compiler that only agrees with itself.
4. **Skip lists, not silence.** Every unfixed case is a named entry with an ID
   and a reason, counted in the exit test. Progress is a number that moves.
5. **No milestone without a CI job.** A check that only runs on a developer's
   machine is not an exit test.

### How the work is cut for agents

A work item is one milestone row that can be finished and verified in a single
session: a chunk writer, one `epp` directive, one lint rule, one instruction
shape. Larger than that and the session ends with half a subsystem and no green
test; smaller and the harness costs more than the change.

Every item follows the same contract:

* **Read the exit test first.** If it does not exist yet, writing it is the
  item.
* **Land the oracle with the feature**, in the same commit. A feature whose
  check arrives later is a feature of unknown correctness.
* **Record the gaps.** Every case the item does not handle becomes a skip-list
  entry with an ID, or a line in this document. A silently skipped case is a
  false pass, and false passes are what make the schedule meaningless.
* **Never weaken a check to finish.** No skipped test, no relaxed assertion, no
  suppressed warning, no `catch` added to turn a failure green. If the check
  itself is wrong, fix it in its own commit and say why in the message.

After A1 the work splits into two tracks that touch different files:

| Track | Milestones | Owns |
|---|---|---|
| Back end | A2, then the emitters for A5 | `org.jerlang.stdlib.beam_lib`, `org.jerlang.compiler.codegen` |
| Front end | A3, A4 | `org.jerlang.compiler.scan`, `.preprocess`, `.parse`, `.lint` |

`BeamData` and `Instruction` are shared by both. A1 changes both, so those
changes land before the split; afterwards an edit to either is a coordination
point rather than a local choice.

Review, by a person or by a separate review pass with the exit test in hand, is
the gate rather than the writing. A change that cannot be shown to match OTP's
behaviour is rejected even when its tests pass. That is deliberately slower than
merging, because a compiler that agrees only with itself is worse than none.

## Decisions to pin now

These are the choices that would otherwise be made differently in each session:

* **Target OTP 29 formats only.** `AtU8` atoms with compact lengths, uncompressed
  `LitT`, the lambda table in `FunT`, `make_fun3`. Declare the maximum opcode the
  emitter actually uses, so an older runtime can still load a simple module.
* **No optimization pass.** Emit straightforward code. `beam_ssa_opt` parity is
  explicitly out of scope, and any time spent there is time not spent on A5.
* **Parse transforms run in a started VM with OTP-shaped compiler options.**
  They are loaded from `-pa` or the code path and run before linting and code
  generation. The options carry `debug_info`, `warnings_as_errors`, include
  directories and macro definitions; the form interface is already OTP's.
* **Native records are gated on the runtime.** The compiler may only emit the
  native-record opcodes once the data type exists; today constructing one raises
  a named error by design.
* **`-spec`, `-type`, `-callback` are stored, not checked.** `dialyzer` is not
  in scope.
* **Version.** The compiler API is public and shipped with 2.0.0. The published artifact,
  download instructions and example builds now target 3.0.0. Future version
  bumps follow `docs/release.md`; no session bumps the version on its own.

## Non-goals

Unchanged from 1.x: no NIFs, no hot code upgrade, no OTP release handling, no
`global`, no TLS distribution. Added for 2.0: no byte-identical output, no type
checker, no Elixir support, no BEAM-to-JVM JIT, and no compiler feature that
exists only to make the compiler's own tests pass.

## Risks

* **The `epp` long tail.** Includes, macro arity and `-if` arithmetic are where
  a front end appears finished long before it is. Budget for it in A3 and keep
  the acceptance number visible.
* **X/Y register bugs.** They do not crash; they return garbage under load. The
  only defence is the corpus, which is why A5's exit test recompiles and runs
  everything rather than a handful of modules.
* **Fun identity.** A wrong `OldUniq` or digest ordering shows up as `badfun`
  over distribution, far from the cause. A1 proves the digest before any fun is
  generated, which is the cheap order.
* **Lint scope creep.** `erl_lint` is large. Match the corpus and the common
  warnings, publish the deviations, and do not chase exotic diagnostics.
* **Implementation-mirroring tests.** The specific failure mode of agent-written
  compilers: tests that assert what the code does rather than what OTP does.
  Rule 3 exists for this.
* **Documentation drift.** Every decision made mid-milestone goes into this file
  in the same commit. Otherwise the next session re-derives it differently.

## Working on this branch

* Status: merged to `master` and released as 2.0.0. This section is retained as
  a record for follow-up compiler work.
* Build and test: `mvn -B test`. CI runs `mvn -B verify`, which adds the packaged
  jars, the runnable all-jar compile smoke test and the japicmp API gate. A single
  milestone's tests should be runnable with `-Dtest=...` and stay under two
  minutes.
* If `mvn` is not on the PATH, the toolchain used here is in `../.tools`:
  a JDK 21 and Maven 3.9.9. Point `JAVA_HOME` at the JDK and run that
  `mvn.cmd`; `-o` works once the local repository is populated.
* Format code goes in `org.jerlang.stdlib.beam_lib`; compiler code goes in
  `org.jerlang.compiler`. Tests mirror the package, as they already do.
* Comments follow `CODE.md`: brackets always, AsciiDoc-style blocks, and a
  comment explains why the code is shaped that way, not what the next line does.
* Update this document in the same commit as the decision it records. A
  milestone's status line changes only when its exit test is green in CI.
