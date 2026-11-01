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
| Scheduler | One scheduler thread per processor with no balancing. A process gets 1,000 instructions per slice, and a CPU-bound loop in its outermost activation yields at the next instruction boundary; `process_info(reductions)` reports the count. Cross-module tail calls switch instruction streams in place, so a tail loop stays preemptible; a yield inside a nested non-tail call is still deferred until that call returns. Run queues are split by max/high/normal/low priority, and `process_flag(priority, ...)` moves a queued process. All timers use one 512-slot, 1 ms timer wheel. |
| Tests | 522 unit/integration tests. Ten use an OTP installation and run in CI's `otp` job; 15 more are opt-in external or machine tests (12 live OTP peers, the pass-rate run, the benchmarks and the soak). The fuzzers run on every push at 200 iterations each and at any count locally, and the 0.5 conformance corpus (`src/test/erl/corpus`) runs in CI. |
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

**Status: complete.**

* [x] Reduction accounting and a 1,000-instruction scheduler quantum. A
  CPU-bound loop in its outermost activation yields at an instruction boundary,
  and `process_info(reductions)` reports the count. `SchedulerFairnessTest`
  runs eight CPU-bound processes on four schedulers and keeps p99 timer wakeup
  under 50 ms.
* [x] Preemption across nested tail calls. A cross-module tail call switches
  module and index in the interpreter loop instead of nesting Java recursion,
  and its return continuations survive a wait or a yield. A yield inside a
  nested non-tail call is still deferred until that call returns.
* [x] Timer wheel for `receive ... after`, `timer:send_after`,
  `erlang:start_timer`. One 512-slot, 1 ms wheel owns every timer, and
  `timer:send_after`, `erlang:cancel_timer` and `erlang:read_timer` use it.
* [x] `process_info/1,2` for the remaining operator fields (status, message
  queue length, memory, current function), `max_heap_size`, and
  `message_queue_data`. Reductions are already reported; the heap and queue
  values are reported and settable, but the runtime does not enforce a heap
  limit yet.
* [x] Priority scheduling that actually reorders the run queue. The four
  queues are strict priority order, and a queued process moves when its
  priority changes.
* [x] Basic tracing: `erlang:trace/3` and `erlang:trace_pattern/3` for call,
  send and receive events, enough for `dbg` and for tests. The match-spec body
  is not evaluated yet; an enabled function traces every call.

**Exit test:** with twice as many CPU-bound processes as schedulers, a ping
process keeps p99 wakeup latency under 50 ms (`SchedulerFairnessTest`, and the
cross-module variant in `SchedulerTailCallTest`); 10k process spawn/exit cycles
leave no entries in the registry; a timer at 10 ms fires within 250 ms through
all four timer entry points; a 500-process link/monitor stress run loses no
signal (`Milestone7Test`).

### 0.8 — Java interop and embedding

**Goal:** a stable, documented ABI in both directions.

**Status: in progress.**

* [x] Freeze the type mapping: Erlang integer/float/atom/binary/list/tuple/map to
  Java types and back, with one documented table and tests per row.
  The table is in `docs/java-interop.md` and `TypeMappingTest` covers each row.
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

**Status: complete.** The exit test below runs in CI: `OperationsTest` covers
the load, metrics and shutdown half, and `OtpReconnectTest` starts an OTP peer,
kills it, restarts it under the same name and waits for this node to reconnect
and carry messages again, in the `otp` job and with no peer started by hand.
The metrics are documented in `docs/operations.md`, including the message
throughput the bullet list above asks for: `jerlang_messages_delivered` counts
deliveries into mailboxes and `jerlang_messages_per_second` reports the average
rate since start.

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

**Status: released.** Tagged `1.0.0` on `074c5b3`. The five bullets below are
implemented and runnable. One part of the exit test was waived: the 24 hour soak
was not run, so the tag was cut on a 330 second soak at target load — 233 million
messages, heap flat at 196 MB, registry drained, final live heap 4 MB. The
waiver and the risk it leaves are recorded in [release.md](release.md), and
closing it is the whole of [1.1](#11--the-24-hour-gate). The fuzzing bullet
below also earned its keep on the way out: the campaign at 20000 iterations found
three loader defects, including a corrupt `.beam` that hung the loader for 23
minutes of CPU.

* [x] Benchmark suite: startup, function call, message passing, binary
  operations and ETS, measured against OTP 29 on the same machine from the same
  Erlang source (`BeamBenchTest`), with both numbers published in
  [benchmarks.md](benchmarks.md). Distribution throughput is the one workload
  listed here that the harness does not yet measure: it needs two peers and a
  second node rather than an inline call, and it is the first row to add after
  the release.
* [x] Budgets set from that baseline, with the roadmap's own example enforced:
  message throughput must stay within 5x BEAM (measured 0.20x) and the CI `bench`
  job fails the build outside it. Interpreted-operation ratios are recorded and
  not gated, and the 213x closures row is named as the next thing to profile.
  No JIT is added: the data so far says the interpreter's per-instruction cost,
  not a missing compiler, is the difference.
* [x] Soak test: configurable duration at target load, with flatness asserted on
  process count, open file descriptors where the platform reports them, and heap
  after collection (`SoakTest`). CI runs thirty minutes nightly; a hosted runner
  is killed after six hours, so the 24 hour gate runs on a machine that stays up
  and the release checklist says so.
* [x] Fuzzing: the BEAM loader and the ETF decoder, deterministic from a seed,
  in CI at 200 iterations and locally at any count (`BeamLoaderFuzzTest`,
  `EtfFuzzTest`). Distribution framing itself is covered by
  `DistListenerTest` at the handshake level but is not in the fuzz campaign
  yet, which is stated in [release.md](release.md). The campaign already paid
  for itself: it found an unbounded allocation in the term reader, where a five
  byte message could ask for a two gigabyte array.
* [x] Packaging: reproducible build (`project.build.outputTimestamp`, checked by
  building twice in `scripts/package.sh`), checksums, optional detached GPG
  signature, semantic versioning with the `japicmp` API gate, a support policy,
  upgrade notes and the release checklist, all in [release.md](release.md).
  The signing key itself is release infrastructure, not something a repository
  can hold.

**Exit test:** the benchmark table, the fuzzing campaign and a soak at target
load are green on the commit that is tagged 1.0, and the artifact is installable
from a clean machine. The day-long soak was dropped from this gate when 1.0
shipped; that is the difference between what was written here and what was done,
and it is carried to 1.1 rather than deleted.

### 1.1 — The 24 hour gate

**Goal:** the day-long soak becomes a gate a release can actually be blocked by,
rather than a command somebody is meant to run on a machine nobody has.

**Status: planned.** 1.0.0 shipped on a 330 second soak. This milestone exists so
the next release does not inherit the same gap, and it is deliberately small: the
soak already asserts process count, descriptors and post-drain heap, so the work
is the machine, the record, and the proof that a failure is caught.

* [ ] A runner that stays up for a day, with the JDK, the OTP version and the
  heap it runs with pinned and written down. A hosted CI runner is killed after
  six hours, so this is a self-hosted runner or a dedicated box, and which of the
  two is a decision this milestone makes rather than inherits.
* [ ] A workflow that runs
  `mvn -Dtest=SoakTest -Djerlang.soak.seconds=86400 -Djerlang.soak.workers=32 test`
  on release candidates, archives the log as an artifact, and fails the commit
  that would be tagged when the run is not green. A run that is only ever
  started by hand is not a gate.
* [ ] A machine-readable sample stream rather than a log to read by eye: one line
  per sample with time, process count, mailbox count, delivered messages, rate,
  heap and descriptors, so two runs can be compared and a slow drift can be seen.
  The current run prints aggregate peaks, which show that something ended badly
  but not when it began.
* [ ] A failing soak that says what grew: when the heap bound is crossed, print
  the structures of the heaviest processes — mailbox depth, frame depth, resume
  points, activations, tail-call continuations, stack and register sizes — plus
  the pending timer count. This was written once while chasing 1.0's apparent
  memory growth and thrown away again; without it a red 24 hour run is a number
  and a shrug.
* [ ] Proof that the gate can fail. A leak injected on purpose — a retention hook
  that keeps a byte per message delivered — has to make the run red at the bound
  on the same commit where the ordinary run is green. A gate that has only ever
  been green is not evidence that it works.
* [ ] The 24 hour run recorded in [release.md](release.md) as run, with its
  result and the hardware it ran on, so the 1.0 waiver ends instead of quietly
  persisting through the 1.x line.

**Exit test:** a 24 hour soak at target load is green on a machine that stayed
up, its sample stream and log are archived with the release, a deliberately
injected leak on the same commit fails the same gate, and the release checklist
contains no waived line.

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
* **The 1.0 waiver.** 1.0.0 shipped without the day-long run, so a leak slower
  than roughly a megabyte a minute would have gone unseen. It is the first item
  in 1.1 for that reason, and until 1.1 lands the 1.x line carries the same gap
  and says so in [release.md](release.md).
* **Hot upgrade.** Deferred, and should be stated as such; production users who
  need it are not served by this runtime yet.
