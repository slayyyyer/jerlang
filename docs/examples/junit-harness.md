# Testing Erlang from JUnit

The first question a team asks about a new runtime is not "what does it do", it
is "can I put it in my build". This example answers that for the smallest
possible case: `.erl` modules, a JUnit 5 suite, and nothing Erlang-shaped
required to run it.

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

## The setup that matters

```java
node = Node.start(NodeOptions.builder().codePath(ebin.toPath()).build());
```

Everything else is ordinary JUnit. A call is a call:

```java
assertEquals(1190, ((Number) Bridge.toJava(
    node.apply("pricing", "with_tax", Integer.of(1000), Integer.of(19)))).intValue());
```

`Node` is `AutoCloseable`, so it wants `@BeforeAll`/`@AfterAll` or
try-with-resources — never one per test. Starting a node starts the schedulers;
the cost is small but it is not free, and a suite that starts one per test will
spend its time in setup. Only one node may be open in a JVM.

Passing the code path through `NodeOptions` is the other half. `Node.start`
resets the module loader, the atom table and the module registry, then applies
that path, so two test classes cannot inherit each other's directories or loaded
modules. The test does not call `CodePaths.reset()` or
`ModuleRegistry.reset()` itself.

## How the `.beam` files are built

There are no committed beams now. `gradle test` runs `compileErlang`, which
compiles `src/main/erl` with JErlang and writes `build/ebin`; the suite reads
that path. A checkout with only a JDK can build and run the tests, and a source
error fails the build instead of falling back to an older artifact.

The task deletes `build/ebin` before compiling, so a failed compile cannot leave
a stale beam for a test to pass against.

## What a test can prove, and what it can not

| Test | Proves |
| --- | --- |
| `quoteMultipliesPriceByQuantity` | Values cross the boundary in both directions. |
| `taxIsAppliedWithIntegerRounding` | Erlang integer arithmetic, not Java's. |
| `totalSumsAList` | Recursion over a list works without OTP. |
| `aProcessKeepsItsState` | `spawn`, `!` and `receive` work under a test runner. |
| `aClauseThatDoesNotMatchSurfacesAsAnException` | A crash fails the build instead of returning `null`. |

The failed clause matters more than it looks: the runtime turns it into a Java
`RuntimeException` at the `apply` call, so a test that forgets an edge case fails
loudly. It is the same path
[`http-service`](http-service.md) catches to answer `500` instead of dropping a
connection.

## The limits, stated plainly

- **No OTP behaviours.** `gen_server`, `supervisor` and `application` live in
  OTP, and running them means putting an OTP installation on the code path. The
  modules here are pure or kernel-only on purpose. For the OTP shape, see
  [`gen-server`](gen-server.md) and the `otp` CI job.
- **No distribution.** The test JVM and the modules under test are the same node;
  there is no peer, so nothing here exercises the handshake.
- **Not a performance harness.** Nothing in this example measures the call cost.
  [`benchmarks`](../benchmarks.md) is the place for numbers.
