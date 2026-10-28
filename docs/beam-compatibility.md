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

`put_list` also had to accept a literal tail, which is how an empty list is
encoded when building a list from a literal and a register.

Instruction arguments also gained the extended tag 5, a typed register
`{tr,Register,Type}`. The type is only used by the JIT, but it has to be
decoded to stay in sync with the instruction stream.

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

## What still blocks other programs

These opcodes are not implemented. They are listed with the constructs that
emit them, because that is what decides which programs can run.

| Number | Opcode | Needed for |
|-------:|--------|------------|
| 159 | `is_tagged_tuple` | Record tests and record patterns |
| 160 | `build_stacktrace` | `try`/`catch` in current compilers |
| 161 | `raw_raise` | Raising exceptions in `try` |
| 162, 163 | `get_hd`, `get_tl` | OTP 26 `hd`/`tl` optimizations |
| 165 | `bs_get_tail` | Binary matching |
| 166, 170 | `bs_start_match3`, `bs_start_match4` | Binary matching (OTP 23+) |
| 167, 168 | `bs_get_position`, `bs_set_position` | Binary matching |
| 173-176 | `recv_marker_*` | `receive` in current compilers |
| 177 | `bs_create_bin` | Binary construction (OTP 26+) |
| 180, 181 | `badrecord`, `update_record` | Record updates |
| 182 | `bs_match` | Binary matching |
| 183, 184 | `executable_line`, `debug_line` | Coverage and debug line information |
| 185 | `bif3` | Three-argument BIF calls |
| 186-191 | native record opcodes | Native records (Erlang/OTP 28+) |

For Elixir this list is the short version of why it cannot run yet: `try`/`rescue`,
binaries and records appear in nearly every Elixir module, and those need
`build_stacktrace`, `raw_raise`, `bs_create_bin` and the `bs_start_match*`
family. Anonymous functions are no longer on that list. The second barrier is
the standard library: Elixir's `Kernel`, `Enum` and the OTP behaviours are
themselves compiled modules that JErlang's kernel and stdlib only partially
replace.

## Not changed

- JErlang does not compile Erlang source; `.beam` files must come from `erlc`.
- The shell still accepts only zero-argument `m:f().` expressions.
