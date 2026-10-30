# Roadmap from 0.4 to production

This replaces the 0.5–1.0 rows of the original roadmap. It is measured against
what the code does today, not against the original intent.

## Where the project actually is

| Area | State |
|------|-------|
| BEAM loading | All significant chunks of an OTP 29 `.beam` file are parsed. `ModuleLoader` searches a code path built from the working directory, `-pa`, `-pz` and `ERL_LIBS`, and `code:which/1`, `lib_dir/1,2`, `priv_dir/1`, `root_dir/0`, `load_file/1`, `ensure_loaded/1`, `ensure_modules_loaded/1`, `is_loaded/1`, `get_path/0`, `add_patha/1`, `add_pathz/1`, `del_path/1`, `replace_path/2`, `stick_dir/1`, `unstick_dir/1` and `is_sticky/1` report and change it. `ensure_modules_loaded/1` loads sequentially: the atom table and module registry are not thread safe. OTP boot scripts are not interpreted. |
| Interpreter | 161 opcode handlers; 191 opcodes known, 30 of them deprecated. Binary matching and construction, records and record updates, `get_hd`/`get_tl`, `bif3`, the coverage line opcodes and the native-record opcodes all have handlers. Native records as a data type are not implemented: constructing one raises a named error. |
| BIFs | 75 `erlang` BIF modules. `lists`, `maps`, `binary`, `string`, `unicode`-adjacent helpers, `base64`, `math`, `timer`, `beam_lib` exist. |
| Kernel | `gen_tcp`, `gen_udp`, `inet`, `net_adm`, `net_kernel`, `rpc`, `os`, and a `file` module with three functions. |
| Distribution | OTP handshake, ETF (including funs, ports, compressed terms), links, monitors, remote spawn, `erpc`/`rpc`, node monitors. No `rex`, no `global`, no TLS distribution, no atom cache. |
| ETS | `set` and `ordered_set`, with named tables and `{keypos, N}`. `new`, `insert`, `lookup`, `delete`, `tab2list`, `update_counter`, `all`, `whereis`, `match`, `match_object`, `select` and `give_away` work. A table is destroyed with its owner, `give_away` moves ownership, table operations are locked, and `update_counter` is atomic across processes. Match specs support patterns, bodies, comparisons, boolean guards and the common type tests; `is_map_key` and arithmetic guards are missing, as are `bag`, `duplicate_bag`, `match_delete`, `select_delete` and heirs. |
| OTP behaviours | OTP's unmodified `gen_server`, `supervisor`, `application`, `proc_lib`, `logger` and `code` run from an OTP installation. `gen_statem` is not verified. The application controller models `.app` loading, dependencies, included applications, start phases, environment and key bookkeeping; `application_master` itself, distributed takeover and automatic restart of a crashed permanent application are not modelled. A dead tree is reaped when the controller is queried, and its included applications are stopped with it. |
| Scheduler | One scheduler thread per processor with no balancing. There are no reductions: a function activation runs to `return` or `wait`, and an intra-module tail call jumps labels without yielding, so a CPU-bound loop can hold a scheduler indefinitely. Cross-module tail calls nest JVM frames. |
| Tests | 466 unit/integration tests. Nine use an OTP installation and run in CI's `otp` job; 15 more are opt-in external or machine tests (12 live OTP peers, the pass-rate run and the benchmarks). The 0.5 conformance corpus (`src/test/erl/corpus`) runs in CI. |
| Conformance | Every one of the 1366 modules that ship with OTP 29.1.2 loads, and every instruction in them has a handler. That is the opcode-level bar, not a language bar: running those modules needs the standard library. |
| CI | GitHub Actions runs `mvn -B test` on pull requests and pushes to `master`, an `otp` job that runs the OTP-backed behaviour tests, and a `conformance` job that installs OTP and enforces the pass-rate floor. |
| Performance | A benchmark suite exists and the 0.5 baseline is recorded in `docs/benchmarks.md`. Interpreted operations are sub-microsecond. A call across the Java/Erlang boundary costs about 0.1 ms, down from 108 ms once the scheduler stopped sleeping for 100 ms on an idle queue; what remains is two thread hand-offs. Nothing is measured against BEAM, so no speed claim is made. |

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

**Status: complete.** The exit test below runs, and `gen_server`, `supervisor`,
`application`, `proc_lib`, `logger`, `code` and the `file` module are loaded
from an OTP 29 installation without changes.

* ~~Module search path: `-pa`, `-pz`, `ERL_LIBS`, `code:which/1`,
  `code:lib_dir/1,2`, `code:priv_dir/1`, `code:load_file/1` and
  `code:is_loaded/1`.~~ `code:ensure_loaded/1`, `code:ensure_modules_loaded/1`,
  `code:replace_path/2`, the sticky directory functions (`stick_dir/1`,
  `unstick_dir/1`, `is_sticky/1`) also work. The loader is sequential because
  module loading is not thread safe, and OTP boot scripts are not interpreted.
* ~~Fill the BIF gaps the standard library needs.~~ `erlang:error/3` with the
  caller's arguments, `persistent_term`, `io:format`, `io_lib:format`, the
  `unicode` module and `min/max` are implemented, and OTP's `logger`
  application boots. The long tail stays a risk for modules nobody ran yet.
* ~~Implement ETS for real.~~ `insert`, `lookup`, `delete`, `tab2list`,
  `update_counter`, `match`, `match_object`, `select`, `give_away` and named
  tables with `{keypos, N}` work for `set` and `ordered_set`. Match specs cover
  the pattern language, bodies and the common guards; `bag`, `duplicate_bag`,
  `match_delete`, `select_delete` and heirs are still missing.
* ~~Start an application: `.app` file parsing, `application:start/1`,
  supervision tree startup and shutdown.~~ The runtime supplies
  `application_controller`; `application.beam` itself is unmodified. Included
  applications are started and stopped with their parent, start phases run in
  order, and a tree that dies on its own leaves `which_applications/0`.
  `application_master` itself, distributed takeover and automatic restart of a
  crashed permanent application are not modelled.

**Exit test:** a supervision tree with one `gen_server` child starts through
`application:start/1`, the child crashes three times and is restarted within
the `{one_for_one, 3, 10}` intensity of the tree, and `application:stop/1`
shuts the supervisor and its child down. The test runs against unmodified
OTP 29 `.beam` files (`ApplicationTest`, plus `SupervisorTest` for the tree on
its own). The `otp` CI job installs OTP 29 and runs this class together with
`GenServerTest`, `ProcLibTest` and `LoggerTest`, so the exit test is enforced
on every push and pull request.

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
  `conformance` job against the OTP installation it sets up, with a 0.99 floor
  so a regression fails the build. At 0.5 the larger corpus is 1366 modules
  from OTP 29.1.2, all loading and all opcode-clean. Opcode counts are not the
  metric. What the number can not say yet is how
  much of that code *runs*: execution needs the standard library, which is
  0.6, and the metric has to grow a run column once it exists.
* **Benchmarks from 0.5.** A number recorded at every milestone makes the 1.0
  performance discussion factual. The 0.5 baseline is in `docs/benchmarks.md`:
  sub-microsecond interpreted operations, and a cross-boundary call at about
  0.1 ms. The first measurement was 108 ms, the scheduler's idle sleep plus a
  10 ms caller poll; both are gone, and the regression is guarded by a test.
  The remaining 0.1 ms is two thread hand-offs, so the next step there would be
  architectural rather than another constant to tune.
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
4. ~~Module search path: `-pa`, `-pz`, `ERL_LIBS`, `code:which/1`,
   `code:lib_dir/1`, `code:priv_dir/1`, `code:load_file/1`.~~
5. Boot OTP's `gen_server` with a minimal callback module; write down every gap
   it hits as the first conformance report.
6. ~~ETS `insert`/`lookup`/`delete`/`tab2list`/`update_counter` for `set`,
   with the `Set` stub replaced; named tables and `{keypos, N}`.~~
   ~~`ordered_set`, then `match`/`select`.~~

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
