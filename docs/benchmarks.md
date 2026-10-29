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

## What the numbers say

Two things, one expected and one not:

* Interpreted operations cost hundreds of nanoseconds. A call is about 700 ns, a
  guard about 400 ns, binary construction about 840 ns. The built-in
  `lists:reverse/1` over 100 elements is 680 ns, which is the same order as a
  single interpreted call: the Java implementations are not the bottleneck.
* A call **across** the boundary costs about **108 ms**, and a spawn plus two
  message exchanges about **525 ms**. That is five orders of magnitude above the
  interpreted call, and it is not the Erlang code: `Scheduler.sleep()` parks an
  idle scheduler for 100 ms, and `Module.apply` polls a spawned process from the
  calling thread every 10 ms. A trivial call therefore waits out at least one
  scheduler sleep.

The second point matters more than any microbenchmark here. "Embed Erlang in a
JVM service" is the beachhead use case for this project, and a 108 ms floor per
cross-boundary call makes that use case unusable for anything interactive. The
fix is mechanical (wake a scheduler on enqueue instead of sleeping, and wait on
the process instead of polling), but it changes scheduling, so it belongs with
the 0.7 runtime work rather than a benchmark commit.

No comparison against BEAM is included. Nothing here was measured against a real
OTP node on the same machine, so no statement about being faster or slower than
BEAM is supported yet.
