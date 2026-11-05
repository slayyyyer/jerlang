# Million processes and a ring

Two workloads, both written in ordinary Erlang and shared unchanged with BEAM:

* `spawn_count(N)` spawns N processes, waits for one message from each, and
  returns `0`.
* `ring(N, Messages)` builds a ring of N processes, sends one token around it
  `Messages` times, and returns `Messages`.

The small JUnit test runs 5,000 spawns and a 128-process ring. It is compiled and
run in CI without an Erlang installation. The full run is opt-in because a
million live processes and ten million message hops measure the machine, not the
build:

```sh
cd examples/concurrency
gradle test                 # small correctness check
gradle run                  # 1,000,000 spawns and 1,000 x 10,000 ring
```

## The BEAM column

The module has no JVM-specific code, so compile it with `erlc` and run the
separate timing shim on the same machine:

```sh
erlc -o build/beam examples/concurrency/src/main/erl/concurrency_demo.erl \
  examples/concurrency/beam_concurrency.erl
erl -noshell -pa build/beam -s beam_concurrency main
```

`beam_concurrency` measures the same two calls with `timer:tc/1`. It does not
import JErlang or use the `java` module. The numbers are recorded in
[the benchmarks page](../../docs/benchmarks.md), including the rows where
JErlang is slower or does not finish.

The large run needs a larger JVM heap than the default on some machines; the
Gradle `run` task sets `-Xmx4g`. That is a deliberate part of the comparison:
BEAM's process memory is shared across its schedulers, while each JErlang process
is a Java object and must fit in the heap.
