# Million processes and a ring

This example asks whether the runtime can carry the concurrency pattern the
thesis depends on: many short-lived processes, and a message token moving
through a ring of processes. The same module is compiled by JErlang and by
`erlc`; neither runtime gets a rewritten workload.

```erlang
spawn_count(N) ->
    Parent = self(),
    spawn_many(N, Parent),
    wait_done(N).

ring(N, Messages) ->
    Parent = self(),
    First = spawn(fun() -> ring_wait(Parent) end),
    Last = build_ring(N - 1, First, Parent),
    First ! {next, Last},
    wait_ready(N, Parent),
    First ! {token, Messages},
    receive {ring_done, 0} -> Messages end.
```

The source is in
[`examples/concurrency`](../../examples/concurrency/README.md). The small JUnit
test runs 5,000 spawns and a 128-process ring in CI without an Erlang
installation. The full run is:

```sh
cd examples/concurrency
gradle run
```

That starts a JErlang node with a 4 GiB heap and runs 1,000,000 spawns followed
by 1,000 processes × 10,000 messages. The BEAM column is the same module
compiled with `erlc`:

```sh
erlc -o build/beam examples/concurrency/src/main/erl/concurrency_demo.erl \
  examples/concurrency/beam_concurrency.erl
erl -noshell -pa build/beam -s beam_concurrency main
```

## Measured on 2026-11-05

Environment: Windows 11, 12th Gen Intel Core i5-12500H, 16 GiB, Temurin
OpenJDK 21.0.12.1, Erlang/OTP 29.1.2 (ERTS 17.1.1), no isolation from other
processes. JErlang used `-Xmx4g`; BEAM used its default settings. Each command
was run once after a build, so these are order-of-magnitude results, not a
statistical comparison.

| Workload | JErlang | BEAM | Ratio |
|---|---:|---:|---:|
| Spawn and join 5,000 processes | 20,872.7 ms | 7 ms | ~2,980x |
| Spawn and join 100,000 processes | did not finish within 5 min | 393 ms | not measured |
| Spawn and join 1,000,000 processes | did not finish within 5 min | 59,000 ms | not measured |
| Ring, 1,000 processes × 1,000 messages | 348.0 ms | 2 ms | 174x |
| Ring, 128 processes × 1,000 messages | 97.6 ms | not measured | not measured |
| Ring, 1,000 processes × 10,000 messages | not measured | 7 ms | not measured |

The 100,000 and 1,000,000 JErlang rows are limits of this prototype, not a
measured ratio. The 5,000-process run is already about 21 seconds; the 100,000
run was stopped after five minutes, and the million-process code has not been
observed to finish on this machine. Extrapolating from 5,000 would not be a
measurement, so the table says “did not finish” instead.

The one result that needs care is BEAM's 59 seconds for one million processes.
On the same machine, 300,000 took 4.6 seconds and 600,000 took 22.5 seconds;
the million-process run is superlinear on Windows, even though it completes.
The JErlang column should therefore be read as “far beyond the tested budget”,
not as a fixed multiple of BEAM.

## What this means

The failure model is present at small scale: spawn, message passing, receive and
supervision work, and the 5,000-process test is deterministic. The process
implementation does not scale to the million-process claim yet. This is a
result about the current Java-backed process representation and scheduler, not
an argument that the Erlang model is unsuitable.

The benchmark is deliberately not part of the normal unit suite: the 5,000 and
128 cases are, but the large runs are not, because they measure the machine and
would make CI slow without adding a correctness signal.
