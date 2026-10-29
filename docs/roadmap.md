# Roadmap from 0.4 to production

This replaces the 0.5–1.0 rows of the original roadmap. It is measured against
what the code does today, not against the original intent.

## Where the project actually is

| Area | State |
|------|-------|
| BEAM loading | All significant chunks of an OTP 29 `.beam` file are parsed. `ModuleLoader` loads `<module>.beam` from the working directory only; `-pa` and `ERL_LIBS` have flag entries but no code path uses them. |
| Interpreter | 161 opcode handlers; 191 opcodes known, 30 of them deprecated. Binary matching and construction, records and record updates, `get_hd`/`get_tl`, `bif3`, the coverage line opcodes and the native-record opcodes all have handlers. Native records as a data type are not implemented: constructing one raises a named error. |
| BIFs | 75 `erlang` BIF modules. `lists`, `maps`, `binary`, `string`, `unicode`-adjacent helpers, `base64`, `math`, `timer`, `beam_lib` exist. |
| Kernel | `gen_tcp`, `gen_udp`, `inet`, `net_adm`, `net_kernel`, `rpc`, `os`, and a `file` module with three functions. |
| Distribution | OTP handshake, ETF (including funs, ports, compressed terms), links, monitors, remote spawn, `erpc`/`rpc`, node monitors. No `rex`, no `global`, no TLS distribution, no atom cache. |
| ETS | `new`, `lookup`, `all`. `insert` is a stub and the file contains 63 `return null` stubs in total, so a created table can never hold an object. |
| OTP behaviours | None. There is no `gen_server`, `supervisor`, `application`, `proc_lib`, `logger`, `code`, or `gen_statem`. |
| Scheduler | One scheduler thread per processor with no balancing. There are no reductions: a function activation runs to `return` or `wait`, and an intra-module tail call jumps labels without yielding, so a CPU-bound loop can hold a scheduler indefinitely. Cross-module tail calls nest JVM frames. |
| Tests | 359 unit/integration tests; 15 opt-in tests that need an external system or measure the machine (12 live OTP peers, the pass-rate run and the benchmarks). The 0.5 conformance corpus (`src/test/erl/corpus`) runs in CI. |
| Conformance | Every one of the 1366 modules that ship with OTP 29.1.2 loads, and every instruction in them has a handler. That is the opcode-level bar, not a language bar: running those modules needs the standard library. |
| CI | GitHub Actions runs `mvn -B test` on pull requests and pushes to `master`, plus a `conformance` job that installs OTP and records the pass rate. |
| Performance | A benchmark suite exists and the 0.5 baseline is recorded in `docs/benchmarks.md`. Interpreted operations are sub-microsecond; a call across the Java/Erlang boundary costs about 108 ms, which is the scheduler's idle sleep, not Erlang. Nothing is measured against BEAM, so no speed claim is made. |

The 0.4 claim — "multi-node applications can be interpreted" — is true for
programs written to the implemented subset, and is proven by `acceptance_demo`
against a real OTP peer. It is not true for arbitrary OTP code: the standard
library is the part that is missing, not the network.

## What production ready means here

"Production ready" is a property of a use case, not of a VM. Two different
targets are possible and they are not the same amount of work:

**A. Replace BEAM for pure-Erlang applications.** Run unmodified OTP
applications, including their supervision trees, on the JVM. This is the honest
compatibility bar, and it is a multi-year effort because it means reproducing
OTP's standard library semantics.

**B. Embed Erlang inside a JVM service.** A single JVM node runs an Erlang
control plane next to Java code, with first-class calls in both directions, and
talks to a real OTP cluster over distribution. This is the differentiator that
motivated the project, and it is achievable on this codebase in a way that A is
not, soon.

The plan below uses **A as the compatibility bar** (it is the only way to know
whether the VM is correct) and **B as the product surface** (it is what a
production user buys). The beachhead release is B with enough of A to run
`gen_server` and `supervisor` unmodified.

Explicit non-goals for 1.0:

* NIF-based OTP applications (`crypto`, `ssl`, `public_key` as shipped). JVM
  code is the replacement for NIFs; bridging to OpenSSL is a separate project.
* Hot code upgrade and OTP release handling.
* `global` name registration and multi-node `global` locks.
* Bytecode compilation as a prerequisite for correctness.

## Milestones

Each milestone has one exit test. A milestone is done when that test runs in CI,
not when its issue list is empty.

### 0.5 — Language completeness

**Goal:** any pure-Erlang module compiled by OTP 29 loads and runs, or fails with
a named BIF/feature gap rather than an unknown opcode.

**Status: done.**

* ~~Binary matching: `bs_start_match3/4`, the `bs_match` command list,
  `bs_get_position`, `bs_set_position` and `bs_get_tail`.~~
* ~~Implement the remaining 14 opcodes: `bs_create_bin`, `update_record`,
  `badrecord`, `get_hd`, `get_tl`, `bif3`, the native-record and coverage-line
  opcodes.~~ Native-record values remain unsupported as a data type and fail
  with a named error, which is the allowed feature-gap form.
* ~~Turn `Unsupported opcode` into an error that names the module, function and
  line.~~
* ~~Keep opcode tests generated from real OTP 29 output.~~

**Exit test:** a checked-in corpus of modules compiled by OTP 29, one per
Erlang construct (binaries, records, maps, bit syntax, comprehensions,
try/catch, funs), runs with zero unsupported opcodes in CI. This is
`ConformanceCorpusTest` and `src/test/erl/corpus`.

### 0.6 — Standard library boot

**Goal:** OTP's own `gen_server`, `supervisor`, `application`, `proc_lib`,
`logger` and `code` modules run unmodified from an OTP 29 installation.

* Module search path: `-pa`, `ERL_LIBS`, `code:which/1`, `code:lib_dir/1`,
  `code:priv_dir/1`, `code:load_file/1`.
* Fill the BIF gaps the standard library needs. Expect a long tail in `file`,
  `io`, `unicode`, `binary`, `erlang:error/3`, `proc_lib`, and `logger`.
* Implement ETS for real: `insert`, `lookup`, `delete`, `match`, `select`,
  `tab2list`, `update_counter`, for `set` and `ordered_set` at minimum.
* Start an application: `.app` file parsing, `application:start/1`, supervision
  tree startup and shutdown.

**Exit test:** a supervision tree with one `gen_server` child starts through
`application:start/1`, the child crashes and is restarted with the configured
intensity, and `application:stop/1` shuts everything down. The same test runs
against unmodified OTP 29 `.beam` files.

### 0.7 — Runtime and concurrency

**Goal:** scheduling and process behaviour are predictable under load.

* Reduction accounting and preemption, including across module boundaries: the
  cross-module tail call must become a trampoline instead of Java recursion.
* Timer wheel for `receive ... after`, `timer:send_after`, `erlang:start_timer`.
* `process_info/1,2` for the fields an operator needs (status, message queue
  length, reductions, memory, current function), `max_heap_size`, and
  `message_queue_data`.
* Priority scheduling that actually reorders the run queue.
* Basic tracing: `erlang:trace/3` and `erlang:trace_pattern/3` for call and
  message events, enough for `dbg` and for tests.

**Exit test:** with twice as many CPU-bound processes as schedulers, a ping
process keeps p99 wakeup latency under 50 ms; 10k process spawn/exit cycles
leave no entries in the registry; a timer at 10 ms fires within a stated bound;
a link/monitor stress test loses no signal.

### 0.8 — Java interop and embedding

**Goal:** a stable, documented ABI in both directions.

* Freeze the type mapping: Erlang integer/float/atom/binary/list/tuple/map to
  Java types and back, with one documented table and tests per row.
* Exceptions: a Java exception becomes an Erlang error with class `error`, the
  original class name and message; an Erlang exit does not kill the JVM.
* Object lifetime: the current reference-handle scheme (`java:new`,
  `java:release`) needs an owner and a documented release policy; no unbounded
  handle tables.
* Overload resolution and static/instance calls by arity and argument types.
* Embedding API: build a node from Java, evaluate a module, register a Java
  function callable from Erlang, call an Erlang fun from Java, and shut down
  deterministically.
* Package as a Maven artifact with examples. Reflection stays available, but
  not on by default in the embedding API.

**Exit test:** two checked-in examples — Java calls an Erlang `gen_server`,
Erlang calls a Java service — run in CI; an API compatibility test fails if a
public signature changes without a version bump.

### 0.9 — Cluster and operations

**Goal:** a node can be run and debugged like a service.

* Logging: OTP `logger` backed by `java.util.logging`/SLF4J, with levels and
  metadata.
* Metrics: process count, run queue depth, reductions, scheduler utilisation,
  message throughput, exposed in a documented format.
* Crash dumps and Erlang-style error reports, including stack frames (already
  implemented) and process state at death.
* Graceful shutdown: stop accepting work, drain, terminate applications in
  order.
* Distribution hardening: reconnection, `rex` compatibility decision, TLS
  distribution if the beachhead needs it, documented node limits.

**Exit test:** a sample service runs under load, exposes metrics, survives a
killed peer and reconnects, and shuts down without losing in-flight calls.

### 1.0 — Performance, packaging, release

**Goal:** a release that can be supported.

* Benchmark suite: startup, function call, message passing, binary operations,
  ETS, distribution throughput. Compare against OTP 29 on the same machine and
  publish both numbers.
* Set budgets from that baseline (for example, no more than 5x BEAM on message
  throughput). Optimise the interpreter only where the profile says so; decide
  on a JIT/bytecode compiler from data, not from the old roadmap.
* Soak test: 24 hours at target load, memory flat, no leaked processes or file
  descriptors.
* Fuzzing: BEAM loader, ETF decoder, distribution framing.
* Packaging: reproducible build, signed artifact, versioning and support policy,
  upgrade notes.

**Exit test:** the benchmark table and the 24 hour soak are green on the same
commit that is tagged 1.0, and the artifact is installable from a clean machine.

## Cross-cutting work

These are not milestones. They start now and continue through 1.0.

* **Conformance corpus and pass-rate metric.** The 0.5 corpus is checked in
  and runs in CI, and the pass rate over a larger corpus is measured in the
  `conformance` job against the OTP installation it sets up. At 0.5 the larger
  corpus is 1366 modules from OTP 29.1.2, all loading and all opcode-clean.
  Opcode counts are not the metric. What the number can not say yet is how
  much of that code *runs*: execution needs the standard library, which is
  0.6, and the metric has to grow a run column once it exists.
* **Benchmarks from 0.5.** A number recorded at every milestone makes the 1.0
  performance discussion factual. The 0.5 baseline is in `docs/benchmarks.md`:
  sub-microsecond interpreted operations, and a 108 ms floor on every call
  across the Java/Erlang boundary because an idle scheduler sleeps for 100 ms.
  Closing that gap is the first item that has a measured reason to exist.
* **CI.** Unit and corpus tests on every push; the live OTP suite and a short
  soak nightly.
* **Documentation per feature.** No documentation milestone at the end; every
  milestone updates `docs/`.

## What changes from the original plan

| Original | Why it does not work as written | Now |
|----------|--------------------------------|-----|
| 0.5 Compiler (BEAM to Java bytecode) | There is no baseline showing the interpreter is too slow, and a compiler freezes semantics work before the semantics are right. | Deferred to a performance decision at 1.0, driven by measurements. |
| 0.6 Scheduler | Came after the compiler; fairness is a correctness issue for the beachhead and must come with the runtime. | 0.7 Runtime and concurrency. |
| 0.7 Compatibility | Too vague, and last. Compatibility is the main risk. | Split into 0.5 (language) and 0.6 (standard library), each with a corpus. |
| 0.8 Documentation | Documentation cannot be a phase after the code. | Continuous, enforced per milestone. |
| 0.9 Performance | No benchmark exists; the milestone had nothing to measure against. | Benchmarks start at 0.5; targets set at 1.0. |
| 1.0 Stability | Stability is not a phase that starts after the work. | 1.0 is a release gate: soak, fuzz, packaging. |

## First slice

In order, each one a small pull request with a test. Steps 1 and 2 are done
(`binary_match.erl`, compiled by OTP 29, covers them):

1. ~~`bs_start_match3/4` and `bs_match`, with modules generated from OTP 29.~~
2. ~~`bs_get_tail`, `bs_get_position`, `bs_set_position`.~~
3. ~~`bs_create_bin`, then `update_record`, `badrecord`, `get_hd`, `get_tl`,
   `bif3`.~~
4. Module search path: `-pa`, `ERL_LIBS`, `code:which/1`, `code:lib_dir/1`.
5. Boot OTP's `gen_server` with a minimal callback module; write down every gap
   it hits as the first conformance report.
6. ETS `insert`/`lookup`/`delete` for `set` and `ordered_set`, with the `Set`
   stub replaced.

Step 5 is the milestone in miniature: if `gen_server` starts, the project has a
credible story; if it does not, the gaps are known and finite.

## Risks

* **The standard library long tail.** Each OTP module may expose several missing
  BIFs. The corpus and the pass-rate metric exist to keep this visible instead
  of discovering it at release.
* **NIFs.** `crypto`, `ssl` and much of `public_key` cannot run as shipped. The
  beachhead must either avoid them or use JVM implementations; say which in the
  release notes.
* **Distributed semantics.** `global`, `rex` and split-brain behaviour are hard
  and are deliberately out of scope until there is a user for them.
* **Elixir.** Binaries and records block it today, and its standard library is
  larger than Erlang's. Treat it as a separate compatibility track with its own
  corpus after 0.6, not as a side effect of Erlang support.
* **Hot upgrade.** Deferred, and should be stated as such; production users who
  need it are not served by this runtime yet.
