# Benchmarks

The roadmap asks for a number recorded at every milestone, so that the
performance discussion at 1.0 is about measurements instead of guesses.

This is the first baseline, recorded at the end of 0.5. It is a starting point
to detect regressions against, not a claim of speed.

## What the harness does

`BenchTest` runs a warmup, then a measured batch, five times per workload, and
reports the median ns/op. Results are printed, never asserted: a shared machine
can not carry a performance budget, and a benchmark that fails the build turns
into a test that gets disabled.

Two execution modes are measured deliberately:

* **Inline workloads** bind a `Process` to the benchmark thread, so
  `Module.apply` runs the opcodes on that thread. Process spawn and scheduler
  hand-off are excluded, so the number is the interpreter.
* **Cross-boundary workloads** call an exported function from a Java thread
  through `Erlang.apply`. The process is spawned on a scheduler and the caller
  waits, so this is the cost an embedding application pays.

Every row also reports the change in stack depth per call. It is `0` everywhere
below, which is the check that a repeated call is not leaking stack frames.

## Running it

```
mvn -Dtest=BenchTest -Djerlang.bench=true test
```

The suite is skipped unless `-Djerlang.bench=true` is set, because it takes
about 15 seconds and measures the machine it runs on rather than the build.

## The baseline

Measured on 2026-10-29, at the end of milestone 0.5, on two consecutive runs.
Run-to-run variation is about 10%, and larger for the closure workload.

Environment: 12th Gen Intel Core i5-12500H, 16 logical processors, 16 GiB,
Windows 11 Home, Temurin OpenJDK 21.0.12.1, default JVM flags, no isolation from
other processes.

| Workload | Run 1 (ns/op) | Run 2 (ns/op) | What it measures |
|---|---:|---:|---|
| Module load (`constructs_demo.beam`) | 321,961 | 315,389 | Chunk parsing, instruction decode, export |
| Function call | 705 | 661 | One interpreted call, one comparison opcode |
| Guard dispatch | 395 | 377 | Call, guard test, clause selection |
| List head (`get_hd`) | 377 | 332 | Pattern match on a list |
| Binary construct | 841 | 833 | `bs_create_bin` with an integer and a binary segment |
| Record update | 398 | 410 | `update_record` |
| Float binary segment | 1,170 | 1,163 | `bs_create_bin` with a float field |
| Big integer | 271 | 264 | 256-bit integer construction |
| Closures and higher order | 3,382 | 3,855 | Fun creation, capture, list comprehension, higher-order calls |
| `lists:reverse/1`, 100 elements | 680 | 621 | The built-in (Java) module path |
| **Java to Erlang call** | **107,991,760** | **106,968,540** | Spawn on a scheduler and wait for the result |
| **Java to Erlang round trip** | **525,043,420** | **509,499,660** | Spawn, two messages, two receives, exit |

Sub-microsecond numbers carry a caveat: at this scale the measurement includes
two `System.nanoTime` calls amortised over the batch and the list construction
done once outside it, so treat them as order of magnitude.

## After removing the scheduler poll

The first baseline found a 108 ms floor under every call from Java. It was not
Erlang: an idle scheduler slept for 100 ms, and the caller polled the spawned
process every 10 ms on top of that. Both were replaced:

* `Scheduler` parks on a condition and `add()` signals it, so a process on the
  run queue is picked up immediately instead of at the next poll.
* `Module.apply` waits on a latch the scheduler counts down when the process is
  done, instead of sleeping and re-checking the state.

Same machine, same harness, after the change:

| Workload | Before | After | Change |
|---|---:|---:|---:|
| Java to Erlang call | 108.0 ms | 0.09-0.16 ms | ~700-1,200x faster |
| Java to Erlang round trip | 525.0 ms | 0.46-0.65 ms | ~800-1,100x faster |

The cross-boundary rows are dominated by OS scheduling and vary more between
runs than the others, hence the range.

Interpreted workloads are unchanged within noise (call 520-680 ns, guard
315-460 ns, list head 270-340 ns, binary construct 630-820 ns, record update
330-410 ns, float segment 970-1,120 ns, big integer 230-270 ns, closures
2.6-3.9 us, `lists:reverse/1` 540-680 ns).

What is left in the 0.1-0.2 ms is OS scheduling: the process runs on a
scheduler thread and the caller waits for it, so two thread hand-offs are paid.
That is inherent to the current design, which runs every process on a
scheduler, and not a polling artefact. Further work would be a different
architecture (running a call on the calling thread), not a smaller sleep.

`CrossBoundaryCallTest` fails if a cross-boundary call takes more than 25 ms,
which leaves two orders of magnitude of headroom over the measured 0.1 ms while
still catching a reintroduced poll. It was checked against the old behaviour
(75 ms median, failed).

## What the numbers say

* Interpreted operations cost hundreds of nanoseconds. A call is about 520 ns, a
  guard about 320 ns, binary construction about 680 ns. The built-in
  `lists:reverse/1` over 100 elements is about 540 ns, the same order as a
  single interpreted call: the Java implementations are not the bottleneck.
* Loading a module is about 260 us: chunk parsing and instruction decode, paid
  once per module.
* A call across the Java/Erlang boundary costs about 0.1-0.2 ms, of which most
  is the two thread hand-offs. That is usable for control-plane work and still
  too slow for a per-item inner loop, which is what the roadmap's embedding
  target assumes.

No comparison against BEAM is included. Nothing here was measured against a real
OTP node on the same machine, so no statement about being faster or slower than
BEAM is supported yet.

No comparison against BEAM is included. Nothing here was measured against a real
OTP node on the same machine, so no statement about being faster or slower than
BEAM is supported yet.
