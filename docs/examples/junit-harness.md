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

## The three lines that matter

```java
CodePaths.reset();
CodePaths.addPatha(CodePaths.normalizePath(ebin.getAbsolutePath()));
node = Node.start();
```

Everything else is ordinary JUnit. A call is a call:

```java
assertEquals(1190, ((Number) Bridge.toJava(
    node.apply("pricing", "with_tax", Integer.of(1000), Integer.of(19)))).intValue());
```

`Node` is `AutoCloseable`, so it wants `@BeforeAll`/`@AfterAll` or
try-with-resources — never one per test. Starting a node starts the schedulers;
the cost is small but it is not free, and a suite that starts one per test will
spend its time in setup.

The `reset()` pair is the other trap. The module loader, the atom table and the
module registry are process-wide state, so a suite where two test classes each
add a directory has to clear the path first or the second class is testing the
first class's modules.

## Why the `.beam` files are in the repository

`src/test/ebin/pricing.beam` and `worker.beam` are committed on purpose. The
claim of this example is that the tests run without an Erlang installation, and
that is only true if the compiled modules ship with the checkout.

`gradle compileErlang` refreshes them when `erlc` is available and skips when it
is not, which is the behaviour a consumer wants in both cases: CI with OTP gets
a fresh compile, a teammate's laptop without OTP still gets a green build.

```
$ gradle test
> Task :compileErlang
erlc: not found, using the committed .beam files
```

This is the same pattern the runtime's own repository uses for
`src/test/resources/bridge_demo.beam`.

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
