# Embedding JErlang in a JVM service

JErlang 2.0 can compile Erlang source to BEAM and embed a node in a Java
process. Those two pieces were not presented as one build-and-run workflow: the
existing examples compiled with `erlc`, the Maven path was not demonstrated, and
an application that follows the documentation had to know which files must be
reset before it can start a node.

3.0 is the embedding release. It makes a JVM service the first-class deployment
shape: the service owns the process, its build compiles the Erlang modules with
JErlang, and no Erlang installation is needed unless the application chooses to
run OTP's own modules or join an OTP cluster. It is not a plan to replace OTP,
and it does not add language features to the compiler.

The target is concrete: a Java team can add one dependency, put `.erl` files
beside its Java sources, run Maven or Gradle on a machine with only a JDK, call
an exported Erlang function from Java, and ship a single service. OTP remains
the compatibility bar for the standard library; it is not a build prerequisite
for a module that only uses the runtime.

## What 2.0 already provides

* `org.jerlang.api.Node` starts and owns a node, `Bridge` maps values in both
  directions, and `Node.registerFunction` exposes a Java lambda to Erlang.
* `org.jerlang.compiler.Compiler` and the `jerlang compile` command turn Erlang
  source into OTP-compatible `.beam` files without invoking `erlc`.
* The `junit-harness` example proves that a JUnit suite can call compiled
  modules, and the service examples prove that Erlang can own a service's
  behaviour.
* The published artifact is runnable with `java -jar` and consumable from
  Maven Central.

## Gaps 3.0 has to close

| Gap | Why it matters |
| --- | --- |
| Consumer builds were tied to `erlc`. | The build still requires an Erlang installation even when the application does not need OTP. |
| No Maven example. | Maven users have no checked-in build that compiles Erlang with the runtime they already depend on. |
| `Node` lifecycle is implicit. | Code paths, module registry resets, startup failures and close order are learned from examples rather than from one documented contract. |
| Packaging is ambiguous. | The shaded jar also declares its dependencies, so consumers can get both bundled and transitive copies. Changing that is a compatibility decision, not a cosmetic one. |
| No migration story. | A team moving from 2.0 has no statement of what stays source-compatible, what changes, or how to verify the move. |

## Principles

1. **The embedded build uses a JDK, not an Erlang toolchain.** If a module only
   uses the built-in runtime, `mvn verify` and `gradle test` must succeed on a
   machine with no `erl` or `erlc` on the PATH. OTP is opt-in at run time.
2. **Compilation is a build step, not a manual preparation step.** A stale beam
   must not survive a failed source compile. The build fails with the
   compiler's diagnostics and no runtime test is treated as green.
3. **The Java API is the contract.** Examples are ordinary consumer projects,
   and each one is exercised by CI rather than described only in prose.
4. **OTP remains the compatibility bar.** Compiling with JErlang does not mean
   reimplementing OTP. Running `gen_server`, `supervisor` or `application`
   still means putting an OTP installation on the code path.
5. **Compatibility changes are deliberate.** 3.0 may change packaging and the
   embedding lifecycle, but every such change comes with a migration note and a
   test that shows the old and new behaviour.

## Milestones

| Milestone | Outcome | Exit test |
| --- | --- | --- |
| E1 — Maven consumer quickstart | A Maven project compiles its own `.erl` with JErlang and calls it from Java. | `mvn verify` passes in a CI job with no Erlang installed; the test fails if compilation is skipped. |
| E2 — Gradle consumer parity | The same workflow exists for Gradle, and the existing Gradle examples no longer need `erlc` for a non-OTP build. | `gradle test` and the example builds pass with no Erlang installed; a clean checkout produces fresh beams. |
| E3 — Embedding lifecycle contract | `Node` has one documented startup, code-path, error and shutdown contract that a service can rely on. | Tests cover startup failure, repeated close, sequential nodes, and a Java callback invoked from Erlang. |
| E4 — Packaging and dependency contract | Consumers get an unambiguous artifact: no duplicated bundled classes, and a runnable form remains available. | A clean consumer resolves the artifact, runs the same example, and the API gate records the deliberate break. |
| E5 — Documentation and release gate | The embedding path, migration guide and release checks are complete. | The full test suite, both consumer builds, the OTP job and the release checklist pass for 3.0.0. |

The order is intentional. E1 and E2 expose build-tool problems before the
runtime API is changed. E3 then fixes the lifecycle those builds depend on,
E4 settles what consumers actually resolve, and E5 closes the release.

## E1 — Maven consumer quickstart

**Status: complete.** The checked-in example is `examples/maven-embed`, and the
`maven-embed` CI job runs it without an Erlang toolchain.

**Goal:** prove the shortest useful embedding path with Maven and no Erlang
installation.

The example is a self-contained project under `examples/maven-embed`:

```
pom.xml
src/main/erl/greeter.erl
src/main/java/example/EmbedDemo.java
src/test/java/example/EmbedDemoTest.java
README.md
```

The POM declares `io.github.slayyyyer:jerlang` as its only runtime dependency and
runs `org.jerlang.compiler.CompilerCommand` during `generate-resources` to write
`target/ebin/greeter.beam`. It does not call `erlc`, does not require
`ERLC`/`ERL` environment variables, and does not use a committed beam.

The Java program:

* starts a `Node` with `target/ebin` supplied through `NodeOptions.codePath`;
* relies on `Node.start` to reset the process-wide path and module registry;
* calls an exported function and converts the result with `Bridge.toJava`;
* exits without leaving schedulers or handles behind.

The test asserts the same call from an ordinary JUnit 5 suite, so a compile that
silently produced no beam fails the build instead of passing on a stale file.

**Exit test:** on a clean checkout with JDK 21 and Maven, and with no `erl` or
`erlc` on the PATH:

```sh
cd examples/maven-embed
mvn -B verify
```

The command compiles the Erlang source with JErlang, runs the test, and fails if
the module is missing. CI runs the same command in a dedicated job that does not
install Erlang/OTP and sets `ERLC`/`ERL` to nonexistent paths. The demo command
`mvn -q compile exec:java` prints `hello, Java` and `6 * 7 = 42`.

**Non-goals for E1:** a Maven plugin, incremental compilation, OTP behaviour
modules, distribution, and a Gradle version of the example. Those are E2-E4
work, and the first milestone should not hide them behind a larger build system
change.

## E2 — Gradle consumer parity

**Status: complete.** The checked-in `examples/gradle-embed` is the parity
quickstart. `junit-harness`, `http-service`, `chat-service` and the JVM side of
`mnesia-service` compile their modules with JErlang; `supervised-app` remains the
explicit `erlc`/OTP example.

**Goal:** make the Maven and Gradle stories equivalent for the smallest
non-OTP service.

* Add a Gradle `compileErlang` task that runs the JErlang compiler through
  `JavaExec`, with the same source and output layout as E1.
* Move `junit-harness` and the non-OTP parts of the service examples off
  `erlc`; keep OTP-specific examples explicit about why they need OTP.
* Make a failed Erlang compile remove the previous beam or fail before tests,
  so a stale module cannot make a broken source look green.
* Document the difference between a consumer build (no Erlang) and a run that
  loads OTP modules (Erlang installation required).

**Exit test:** a clean checkout runs `gradle test` and the non-OTP example builds
with no `erl`/`erlc` available. CI has a job for that path, separate from the
existing `gradle-examples` job that installs OTP. Local verification on JDK 21
ran `gradle test` in `gradle-embed` and `junit-harness`, and `gradle installDist`
in `http-service`, `chat-service` and `mnesia-service`, with both tool paths set
to nonexistent files.

## E3 — Embedding lifecycle contract

**Status: complete.** The API is in `NodeOptions` and `Node`; the examples now
use it instead of copying the old reset recipe.

**Goal:** make `Node` safe to use as a library in a long-lived Java service.

The contract is:

* `NodeOptions.codePath(Path)` and `NodeOptions.otpRoot(Path)` supply startup
  paths. The OTP root is `code:root_dir()`; the node finds the versioned
  `kernel` and `stdlib` ebins below it.
* One node may be open in a JVM. `Node.start` rejects a second one and resets
  the process-wide code path and module registry before applying its options,
  so sequential nodes do not inherit state.
* A failed start shuts down whatever VM work was created, resets the handle cap,
  code path and module registry, and leaves no active node.
* `close()` is idempotent. It stops applications and processes before clearing
  the modules they use, drains the handle table, resets the code path and module
  registry, and clears the active node.
* `registerFunction` maps a callback `RuntimeException` to
  `error({java_exception, ClassName, Message})`. `apply` has a `Duration`
  overload that throws `Module.ApplyTimeoutException` after killing the process
  that ran the call.

**Exit test:** `NodeTest` starts and closes a node repeatedly, rejects a second
open node, injects an invalid OTP-root startup failure, verifies a failed start
can be followed by a successful one, registers callbacks that throw and time out,
asserts a closed node rejects calls, and keeps a node alive across several
requests. The Maven, Gradle and service examples use `NodeOptions` paths and no
longer call `CodePaths.reset()` or `ModuleRegistry.reset()`.

## E4 — Packaging and dependency contract

**Goal:** make the artifact a consumer can reason about.

The current shaded jar is convenient: `java -jar jerlang-2.0.0.jar` works. It
also declares its dependencies in the POM, so a Maven or Gradle consumer can
receive both the bundled classes and the transitive libraries. E4 decides the
3.0 shape and records the decision:

* keep a thin library artifact as the normal dependency;
* publish a runnable/uber artifact under a classifier for the shell and
  one-file workflows;
* ensure the documented examples resolve only the thin artifact;
* keep the executable jar available for users who want it, without making every
  embedder pay for a duplicate class path.

This is a packaging break, so it is one of the reasons the release is 3.0.0
rather than 2.1.0. The API gate and the migration guide must name the change.

**Exit test:** a clean consumer builds the Maven and Gradle examples against the
new coordinates, `java -jar` still starts the shell from the runnable artifact,
and a dependency-tree check shows no duplicate JErlang classes.

## E5 — Documentation and release gate

**Goal:** make the embedding story the front door without losing the OTP story.

* Add the Maven and Gradle quickstarts to the documentation site.
* Write a 2.0-to-3.0 migration guide covering packaging and lifecycle changes.
* State which paths require an Erlang installation and which do not.
* Add the embedding examples to the release checklist and run them from a clean
  checkout.
* Keep the OTP-backed tests, conformance job, fuzzing and soak as separate
  gates; embedding must not weaken them.

**Exit test:** the 3.0.0 release checklist is executed from a clean checkout:
the main suite, the API gate, the Maven example, the Gradle example, the OTP
job and the packaged-jar smoke test all pass, and the release notes state the
supported Java versions and the OTP boundary.

## Definition of done for 3.0

The release is ready when all of the following are true:

* A Maven service and a Gradle service compile `.erl` with JErlang and run it
  from Java on a machine with no Erlang installation.
* The embedding examples are CI tests, not documentation-only snippets.
* `Node` has a documented, tested lifecycle that does not require copying
  process-wide resets from an example.
* The artifact coordinates and the migration from 2.0 are explicit.
* The OTP compatibility, conformance, fuzzing and soak gates still pass.

## What 3.0 will not do

* It will not add NIFs, hot code upgrade, OTP release handling, TLS
  distribution or `global`.
* It will not replace an OTP node or promise that every OTP application runs
  without an Erlang installation.
* It will not make the compiler a type checker or a `dialyzer` replacement.
* It will not add language features that exist only to make the embedding
  examples shorter.
