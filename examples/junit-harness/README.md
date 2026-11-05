# Testing Erlang from JUnit

The shortest path from "this looks interesting" to "my build runs it": Erlang
modules under test, called from an ordinary JUnit 5 suite, with the published
artifact and no Erlang installation needed to run the tests.

```sh
cd examples/junit-harness
gradle test
```

```
PricingTest > aProcessKeepsItsState() PASSED
PricingTest > quoteMultipliesPriceByQuantity() PASSED
PricingTest > totalSumsAList() PASSED
PricingTest > taxIsAppliedWithIntegerRounding() PASSED
PricingTest > aClauseThatDoesNotMatchSurfacesAsAnException() PASSED

BUILD SUCCESSFUL in 8s
```

## How it works

`PricingTest` starts one `Node` in `@BeforeAll`, points the code path at the
compiled modules and calls them like any other function under test:

```java
node = Node.start();
assertEquals(1000, Bridge.toJava(node.apply("pricing", "quote",
    Integer.of(250), Integer.of(4))));
```

Two things are easy to get wrong, and both are in the test:

- **One node per class, not per test.** Creating a node starts the schedulers;
  doing that around every test would dominate the run.
- **Reset the code path first.** The module loader, the atom table and the
  registry are process-wide, so a suite that adds directories in several classes
  needs `CodePaths.reset()` and `ModuleRegistry.reset()`.

## The tests

| Test | What it proves |
| --- | --- |
| `quoteMultipliesPriceByQuantity` | A pure function crosses the boundary and returns an integer. |
| `taxIsAppliedWithIntegerRounding` | Integer arithmetic behaves like Erlang, not like Java's `int` truncation rules. |
| `totalSumsAList` | A recursion over a list works with no OTP installation present. |
| `aProcessKeepsItsState` | `spawn`, send and receive work from a JUnit run: the counter lives in a process, not a field. |
| `aClauseThatDoesNotMatchSurfacesAsAnException` | A failed clause is a `RuntimeException` in the test, so a broken module fails the build. |

## Why the beams are built by the test build

`src/test/ebin` used to contain committed beams; it does not now. `gradle test`
runs `compileErlang`, which compiles `src/main/erl` with JErlang and writes
`build/ebin`; the suite reads that path. There is no `erlc` step and no fallback
to a checked-in artifact, so a clean checkout with only a JDK builds and tests
the modules.

The task deletes `build/ebin` before compiling, so a source error fails the test
build instead of leaving an older module in place.

## What this example does not show

The modules are pure or kernel-only — no `gen_server`, no `supervisor`, no
`application` — because those live in OTP and running them needs an OTP
installation on the code path. If your modules are OTP behaviours, see
[`gen-server`](../gen-server) and the `otp` CI job, which add OTP's `stdlib` and
`kernel` to the path.

Both node and test live in one JVM, so this also does not need distribution: the
call is a direct one, not an RPC.
