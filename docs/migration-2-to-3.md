# Migrating from 2.0 to 3.0

JErlang 3.0 is the embedding release. The Java API in `org.jerlang.api` stays
source-compatible, but a consumer has two things to change: what the normal
dependency resolves to, and the way a node is started and closed.

This guide is the migration path for an application that embeds 2.0. It does
not add language features or change the OTP boundary; those remain as described
in [embedding.md](embedding.md) and [release.md](release.md).

## Who needs to act

- **Maven and Gradle consumers** keep the same coordinates, but now receive the
  thin library jar instead of the shaded jar.
- **Anyone running `java -jar` on the main artifact** must use the `all`
  classifier instead.
- **Code that copied the 2.0 example's process-wide reset** must stop doing that
  and pass paths through `NodeOptions`.
- **A process that opens more than one node at a time** must close one before
  starting the next; one active node per JVM is now the contract.

## 1. Packaging

The two artifacts have different jobs:

| Artifact | Use |
| --- | --- |
| `jerlang-3.0.0.jar` | The normal Maven/Gradle dependency. It contains JErlang and declares `jzlib`, `jnr-posix`, `jline` and `commons-math3` as ordinary transitive dependencies. |
| `jerlang-3.0.0-all.jar` | The runnable artifact, attached under the `all` classifier. It bundles the dependencies and has the `Main-Class` used by `java -jar` and the `compile` command. |

For Maven, the dependency is unchanged:

```xml
<dependency>
  <groupId>io.github.slayyyyer</groupId>
  <artifactId>jerlang</artifactId>
  <version>3.0.0</version>
</dependency>
```

For Gradle:

```kotlin
implementation("io.github.slayyyyer:jerlang:3.0.0")
```

Remove any exclusions or relocations that existed only to work around the 2.0
jar bundling `jzlib`, `jnr-posix`, `jline` or `commons-math3` while also
declaring them. Check that the dependency tree contains one JErlang artifact and
no bundled copies of those libraries.

Change a `java -jar` launch from:

```sh
java -jar jerlang-2.0.0.jar
```

to:

```sh
java -jar jerlang-3.0.0-all.jar
```

The main jar intentionally has no `Main-Class` now. A dependency-managed
application uses the thin jar and its normal classpath, not the `all` jar.

## 2. Node lifecycle

If 2.0 code copied the example's `CodePaths.reset()` or
`ModuleRegistry.reset()` setup, remove it. `Node.start(NodeOptions...)` resets
the process-wide code path and module registry before applying its options, so
the lifecycle is one call rather than a recipe copied between applications.

```java
try (Node node = Node.start(NodeOptions.builder()
        .codePath(Path.of("build/ebin"))
        .build())) {
    Term result = node.apply("greeter", "greet",
        Bridge.toTerm("Java".getBytes(StandardCharsets.UTF_8)));
    System.out.println(Bridge.toJava(result));
}
```

The contract a service can rely on:

- `NodeOptions.codePath(...)` supplies application beams.
- `NodeOptions.otpRoot(...)` supplies the OTP installation and adds its
  `kernel` and `stdlib` ebins when the application runs OTP's own modules.
- One node may be open in a JVM. A second `Node.start` is rejected; close the
  first node before starting the next.
- A failed start rolls back the VM work it created and leaves no active node.
- `close()` is idempotent. It stops applications and processes, drains the
  handle table, and clears the code path and module registry.
- `Module.apply` has `Duration` overloads that kill the process that overran the
  timeout.
- Reflection is opt-in. Use `NodeOptions.reflection(true)` only when Erlang code
  must reach JVM classes directly; `registerFunction` is the narrower binding.

## 3. Which paths need Erlang

The build-tool path needs only a JDK when the module uses JErlang's built-in
modules. OTP is a runtime dependency of the application, not of the build.

| Work | Erlang/OTP installation |
| --- | --- |
| Compile and run a module that uses only the built-in modules | No |
| Compile a module whose `-include` or `-include_lib` files come from OTP | Yes, or a copy of those headers |
| Run `gen_server`, `supervisor`, `application`, `proc_lib` or `logger` | Yes; pass `NodeOptions.otpRoot(...)` |
| Join an OTP cluster | Yes on the OTP peer; the JErlang node uses plain TCP distribution |
| Use NIF-backed `crypto`, `ssl` or `public_key` | Not supported; use explicit JDK APIs for application code |

The checked-in [Maven example](https://github.com/slayyyyer/jerlang/tree/master/examples/maven-embed)
and [Gradle example](https://github.com/slayyyyer/jerlang/tree/master/examples/gradle-embed)
cover the no-Erlang path. The OTP examples remain explicit about needing an
installation.

## 4. Verify the migration

On a clean checkout, install the release candidate and run both consumer
quickstarts through the gate:

```sh
scripts/check-embedding.sh
```

The script points `ERL` and `ERLC` at paths that do not exist, runs
`mvn -B clean verify` in `examples/maven-embed`, and runs
`gradle --no-daemon clean test` in `examples/gradle-embed` with a temporary
`mavenLocal` init script. It must finish with `embedding consumer checks passed`.

Then run the library's own gates from the repository root:

```sh
mvn -B verify
```

For a released artifact, the same consumer commands work against Maven Central
without the temporary init script. For a local release candidate, the script
installs the thin artifact first.

## 5. Rollback

If the migration is not possible yet, 2.0.0 remains available on Maven Central.
Pinning 2.0.0 keeps the old shaded main jar; it also keeps the old lifecycle
recipe and does not receive the 3.0 fixes. Do not mix 2.0 and 3.0 artifacts in
one classpath.
