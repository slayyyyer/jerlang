# Running current Erlang/OTP BEAM files on JErlang

JErlang's interpreter was written against OTP 20-era BEAM files. This document
records what a module compiled by a current compiler needs, what had to change
to run one, and what still does not work.

## Compiling

JErlang has no compiler of its own, so `.beam` files are produced with `erlc`
from an Erlang/OTP installation and loaded from the working directory:

```
erlc -o src/test/resources src/test/erl/bridge_demo.erl
```

`src/test/resources/bridge_demo.beam` is the result of compiling
[`src/test/erl/bridge_demo.erl`](../src/test/erl/bridge_demo.erl) with
Erlang/OTP 29.1.2, and
[`ErlangSourceBridgeTest`](../src/test/java/org/jerlang/jvm/ErlangSourceBridgeTest.java)
runs it.

## What a modern BEAM file contains that the loader did not expect

### Atoms are UTF-8 and may use compact lengths

OTP 20 introduced the `AtU8` chunk. OTP 28 changed its format: the atom count is
written as a negative number, and each atom length is encoded with the compact
instruction encoding rather than a single byte. The reader now handles `Atom`
(latin-1) and `AtU8` (UTF-8), including the negative count.

### Literals may be uncompressed

OTP 28 stopped compressing the `LitT` chunk. A zero word after the chunk header
now means "the literals follow uncompressed"; the older format still starts with
the uncompressed size and a zlib stream. Both are read.

### New chunks carry no code

`Meta`, `Dbgi` and `Type` are written by current compilers. They are not needed
to execute a module, so they are skipped. A chunk that fails to load now only
loses its own data instead of failing the whole module.

### The line table stores filename indexes

Line items are encoded with the `a` tag for the filename and the `i` tag for the
line number, but `a` carries an **index into the chunk's own filename table**,
not an index into the atom chunk. Decoding it with the instruction argument
reader dereferenced a null atom chunk and aborted the load. The reader now
decodes the tags directly and resolves filenames after reading the table.

### Opcode numbers are not enum positions

Opcode numbers come from `lib/compiler/src/genop.tab`, and new opcodes are
inserted between existing ones. `Opcode.decode` used the enum position, which
happens to work only while no opcode has been inserted. It now looks up the
declared code, and an opcode this interpreter does not know fails with its
number. The `maxOpcode` header in the `Code` chunk is reported, but no longer
rejects the module before decoding.

## Instructions that had to be added

| Opcode | Number | Emitted for | Notes |
|--------|:------:|-------------|-------|
| `put_tuple2` | 164 | Tuple construction (OTP 22+) | Replaces `put_tuple`/`put` pairs |
| `swap` | 169 | Register swaps (OTP 22+) | |
| `make_fun3` | 171 | Anonymous functions (OTP 24+) | 3 operands in the file; the disassembler expands them |
| `init_yregs` | 172 | Frame initialization (OTP 22+) | Sets Y registers to `nil` |
| `call_fun2` | 178 | Calling anonymous functions (OTP 24+) | Tag and type operands are ignored |
| `build_stacktrace` | 160 | `try ... catch Class:Reason:Stack` (OTP 22+) | Normalises the recorded frame list |
| `raw_raise` | 161 | `erlang:raise/3` in a catch clause (OTP 22+) | Class, reason and stacktrace are re-raised unchanged |
| `is_tagged_tuple` | 159 | Tuple patterns and record tests (OTP 22+) | Checks arity and the first element |
| `recv_marker_bind` | 173 | A second `receive` in one function (OTP 28+) | Records nothing: `Process.receivePosition` tracks the scan position |
| `recv_marker_clear` | 174 | Leaving a marked `receive` | Nothing to clear, for the same reason |
| `recv_marker_reserve` | 175 | A second `receive` in one function (OTP 28+) | Allocates the marker reference |
| `recv_marker_use` | 176 | A second `receive` in one function (OTP 28+) | Falls through to the `loop_rec` that follows it |
| `bs_start_match3` | 166 | Starting a binary match (OTP 22+) | The source is stored as a one element list; a match context is copied |
| `bs_start_match4` | 170 | Starting a match on a known matchable value (OTP 23+) | `no_fail` and `resume` modes; a label fails like `bs_start_match3` |
| `bs_match` | 182 | Binary matching (OTP 26+) | Carries the get, skip and literal compare operations as a flat command list |
| `bs_get_position` | 167 | Saving a match position (OTP 22+) | The position is the bit offset |
| `bs_set_position` | 168 | Restoring a match position (OTP 22+) | |
| `bs_get_tail` | 165 | Capturing the rest of a match (OTP 22+) | A binary when byte aligned, otherwise a bitstring |
| `get_hd` | 162 | List patterns and `hd` optimizations (OTP 22+) | Raises `badarg` for a non-list or empty list |
| `get_tl` | 163 | List patterns and `tl` optimizations (OTP 22+) | Raises `badarg` for a non-list or empty list |
| `bs_create_bin` | 177 | Binary construction (OTP 25+) | Reads the flat segment list, including the string table offsets |
| `nif_start` | 179 | Start of a `-nifs()` function | No-op: this runtime has no NIFs |
| `badrecord` | 180 | A record pattern that does not match | Raises `error:{badrecord,Value}` |
| `update_record` | 181 | `Record#name{field = Value}` (OTP 26+) | Applies the flat `[Index, Value]` update list |
| `executable_line` | 183 | Line information for coverage (OTP 27+) | Sets the current frame's location |
| `debug_line` | 184 | Breakpoint locations (OTP 28+) | Sets the current frame's location |
| `bif3` | 185 | Three-argument BIF calls that can fail (OTP 29+) | Arguments and destination, like `bif2` |
| `is_any_native_record` | 186 | Native-record tests (OTP 29) | Always fails: no native record values exist in this runtime |
| `is_native_record` | 187 | Native-record tests (OTP 29) | Always fails |
| `get_record_elements` | 188 | Native-record field access (OTP 29) | Always fails |
| `put_record` | 189 | Native-record construction (OTP 29) | Raises a named `native records are not supported` error |
| `is_record_accessible` | 190 | Native-record access checks (OTP 29) | Always fails |
| `get_record_field` | 191 | Native-record field access (OTP 29) | Jumps to the fail label, or raises `badarg` when the label is 0 |

`put_list` also had to accept a literal tail, which is how an empty list is
encoded when building a list from a literal and a register.

A literal tuple is an operand too. `move` used to treat any two element tuple
source as a register, so a function returning `{ok, Value}` or `{[], []}`
failed with "Cannot convert to integer". Operands now go through `toArg`,
which treats only `{x,N}`, `{y,N}` and `{fr,N}` as registers and returns any
other tuple unchanged.

Instruction arguments also gained the extended tag 5, a typed register
`{tr,Register,Type}`. The type is only used by the JIT, but it has to be
decoded to stay in sync with the instruction stream.

OTP 28 also made the heap need of `allocate_heap` (and `test_heap`) a list of
heap fragments such as `{alloc, [{words, 0}, {floats, 0}, {funs, 1}]}`
instead of a word count. The JVM manages the heap, so the interpreter reads
only the stack need and the live-register count and ignores the fragment
description.

### Binary matching

OTP 26 stopped emitting the individual `bs_get_integer2` and
`bs_get_binary2` instructions for a match and folded them into one `bs_match`
instruction with a command list. The interpreter reads that list in order:
`ensure_at_least`, `ensure_exactly`, `skip`, `get_tail`, `=:=`, `integer`,
`binary` and `float`. A command whose field does not fit jumps to the fail
label of the instruction.

Fields can be signed, little endian or big endian, and a match can start at a
bit offset: `<<A:3, B:5, Rest/bitstring>>` works because `get_tail` returns a
bitstring when the rest is not a whole number of bytes. The position
instructions save the bit offset, so a failed match restores the start and
`bs_get_tail` can capture the original value for the failure reason.

Limitations: little endian fields must be a whole number of bytes, `native` is
treated as little endian, and UTF fields still go through their own
`bs_get_utf*`/`bs_skip_utf*` instructions rather than through `bs_match`
commands.

### Binary construction

`bs_create_bin` carries a destination and a flat list of six-word segments:
`[Type, Seg, Unit, Flags, Value, Size]`. Type is `integer`, `float`, `binary`,
`append`, `string`, `utf8`, `utf16` or `utf32`; flags are `little`, `signed`
and `unsigned` where they apply. The interpreter writes each segment into a
`BitStringOutputStream`, so an unaligned bitstring segment stays unaligned.

A `string` segment is not inline data: `beam_asm` encodes it as an offset into
the module's `StrT` chunk, and `Size` is the byte count. The interpreter reads
that chunk, which is why binary construction needed the string table that
`bs_match_string` already used.

### Function clauses

A clause that fails its pattern does not jump to a normal label: it jumps back
to the label in front of the function's `func_info`. The interpreter used to
re-enter the function and loop forever. It now recognises those entry labels
and raises `error:function_clause`, so a single-clause function called with the
wrong argument terminates the process instead of hanging a scheduler.

### Comparisons

A comparison in a function body compiles to a `bif` instruction that calls
`erlang:'<'/2` and friends, while the same test in a guard compiles to
`is_lt`, `is_ge`, `is_eq`, `is_ne`, `is_eq_exact` or `is_ne_exact`. The
operands may be literals, X registers or Y registers, on either side: `X < 12`,
`12 < X` and `A < B` all appear in compiled code.

Both paths share `TermOrder`, which implements the Erlang term order
(number < atom < reference < fun < port < pid < tuple < map < nil < list <
bitstring). `==` and `/=` compare numbers by value, so `1 == 1.0` is true,
while `=:=` and `=/=` stay exact. The guard instructions used to handle only
one literal/register shape and raised `Can not compare` for every other order.

### Receive scan position

`loop_rec_end` used to remove the head of the mailbox. When the first message
did not match a receive clause, it was therefore lost before a later receive
could see it. `Process` now keeps `receivePosition` while a receive scans the
mailbox: `loop_rec_end` advances it, `remove_message` removes the message at
that position and resets the scan, and a timeout resets it as well. This is
what lets `spawn_opt(..., [link, monitor])` receive the `DOWN` that follows an
`EXIT` in the same mailbox.

## Fun calls

`make_fun3` carries a lambda index, a destination and an explicit list of free
variables, rather than reading the free variables from X0 to X(NumFree-1) the
way `make_fun2` does. The lambda table entry still starts with the function's
atom, arity, label, index, free-variable count and unique value, so the label
and the free-variable count come from the same table the older instruction uses;
only the instruction encoding changed. The disassembler prints five operands
because it resolves the atom and the unique value into the instruction.

`call_fun` and `call_fun2` were also setting the continuation pointer to
`index + 1`, while `call` sets it to the index of the call instruction and the
interpreter loop advances past it. Every call through a fun therefore skipped
the instruction after the call: in `funs_demo`, `run/0` skipped the `swap` that
moved the fun into the argument register and passed a number instead.

### Fun identity on the wire

`term_to_binary/1` writes an internal fun as `NEW_FUN_EXT`, which names the
module version the fun belongs to by a 16 byte digest and the index of the
lambda in the module's function table. The digest is the value
`beam_lib:md5/1` returns, so it has to be computed the way `beam_lib`
computes it: over the significant chunks in the order `md5_chunks/0` lists
them, not in file order, with the `OldUniq` of every lambda table record
replaced by zero. `OldIndex` and `OldUniq` themselves are ordinary integer
terms in the term stream. A receiver checks the digest against the module
it has loaded and rejects a mismatch with `badfun`, so a node that holds
an older compile of a module can not run a fun that was built from a
newer one.

## Tail calls

`call_ext_only` and `call_ext_last` used to end the process with their result,
so a function that ended in a tail call never returned to its caller. They now
return to the caller's continuation pointer. Functions applied synchronously
from Java (`Module.apply`, which runs in the same process without sharing its
instruction stream) keep the old behaviour, tracked by `Process.applyDepth()`;
returning a continuation pointer into an instruction stream the callee does not
belong to is what broke `apply:test/1`.

`apply_last` still ends the invocation: it tears down its own frame, and
returning to the continuation pointer double-deallocates.

## Exceptions

An OTP 29 `try` is compiled into `try` (install a handler), `try_end` (leave the
block normally), `try_case` (enter the catch clauses) and, when the class does
not match, `raise`, which re-raises the class left in X0, taking the stacktrace
and then the reason as operands. `catch Class:Reason:Stack` additionally emits
`build_stacktrace` (turn the raw stacktrace in X0 into a list) and `raw_raise`
(re-raise X0/X1/X2 unchanged).

JErlang's `Error` now carries the exception class (`error`, `throw` or `exit`),
the reason and the stacktrace, and handlers put them in X0, X1 and X2, which is
what `try_case` reads. Handlers form a chain, so a nested `try` restores the
enclosing one when it is entered or left.

An exception raised in a called function unwinds to the handler that installed
the `try`: the interpreter drops the callee's stack frame and restores the
continuation pointer saved at the `try`, so the catch clause returns to the
right caller. A `catch` expression has no handler code of its own and continues
after the caught call, so it leaves the frame alone.

An exception that reaches the top of a process terminates that process with
its reason instead of stopping the virtual machine. The scheduler then sends
the reason to the process's links as exit signals and to its monitors as
`DOWN` messages.

The scheduler also prints an Erlang-style error report, on standard output
like OTP's default logger handler, with the stacktrace captured at the raise:

```text
=ERROR REPORT==== 2026-10-28::17:00:00.000000 ===
Error in process <0.33.0> with exit value:
{{badmatch,2},[{crash_demo,crash_now,1,[{file,"crash_demo.erl"},{line,19}]}]}
```

The reason carried to links and monitors is the same value printed here. An
uncaught error terminates the process with `{Reason, Stacktrace}`, and an
uncaught `throw` with `{{nocatch, Value}, Stacktrace}`; `erlang:throw/1`
raises the `throw` class that makes the `nocatch` wrapper possible. `exit/1`
and a process killed by an exit signal keep their plain reason, and because
they are normal ways to stop, print nothing.

## Stack frames

`Process` keeps one activation per Erlang function call. A frame is recorded
when a function is entered through the export table (`Module.apply`) or through
a local, fun or dynamic call, and is dropped by `return`, by a tail call that
replaces the activation, or by an exception that unwinds to a handler. The
stacktrace at a raise is therefore a real list, youngest call first, with each
frame shaped like the ones the BEAM emulator produces:

```
{try_demo,caught,1,[{file,"try_demo.erl"},{line,14}]}
{try_demo,run,0,[{file,"try_demo.erl"},{line,67}]}
```

The location comes from the module's line table. Line references are 1-based
and `0` means "no source location"; when the line table has no filename table,
the base name of the compile info chunk's `source` entry is used. BIFs do not
get a frame, matching the BEAM, and `raw_raise` re-raises the frames captured at
the original raise.

[`try_demo.erl`](../src/test/erl/try_demo.erl) covers all of this, and
[`TryCatchTest`](../src/test/java/org/jerlang/jvm/TryCatchTest.java) runs it.

## What still blocks other programs

Every opcode a current `erlc` emits has a handler: 191 opcodes are known, 30
of them are the old deprecated arithmetic and bit-syntax instructions, and the
remaining 161 all execute. There is no "unsupported opcode" list for OTP 29
programs any more; the conformance corpus in
[`src/test/erl/corpus`](../src/test/erl/corpus) runs in CI and is the exit test
for this.

The one language feature still missing is **native records**, the experimental
OTP 29 data type declared as `-record #name{...}`. A native record is a
distinct runtime type that captures its field definition; implementing it needs
a new term type, not another opcode. The type-test opcodes therefore always
fail, field extraction jumps to its fail label, and construction raises
`error:"native records are not supported by this runtime"`. Modules that do not
use native records are unaffected.

For Elixir, binary construction, record updates and binary matching now work,
and anonymous functions and `try`/`rescue` already did. The remaining barrier
is the standard library: Elixir's `Kernel`, `Enum` and the OTP behaviours are
themselves compiled modules that JErlang's kernel and stdlib only partially
replace.

## Not changed

- JErlang does not compile Erlang source; `.beam` files must come from `erlc`.
- The shell still accepts only zero-argument `m:f().` expressions.
