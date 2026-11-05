# Getting started

JErlang runs compiled Erlang on the JVM and ships its own compiler. The
workflow is the same either way: compile to a `.beam`, then load it. The
`compile` command needs no Erlang installation; beams produced by `erlc` still
load unchanged.

Two things to know before starting:

- **Java 21 or newer** is required, for building and for running.
- **Erlang/OTP 29** is needed at run time if the code uses OTP's own modules
  (`gen_server`, `supervisor`, `application`, `logger`, …) or connects to an
  OTP node, and at compile time only if it includes OTP headers. A module that
  only uses `erlang`, `lists`, `maps` and the other built-in modules needs no
  Erlang installation at all.

## Install

=== "Maven"

    ```xml
    <dependency>
      <groupId>io.github.slayyyyer</groupId>
      <artifactId>jerlang</artifactId>
      <version>3.0.0</version>
    </dependency>
    ```

=== "Gradle"

    ```kotlin
    implementation("io.github.slayyyyer:jerlang:3.0.0")
    ```

=== "Runnable jar"

    ```sh
    curl -sO https://repo1.maven.org/maven2/io/github/slayyyyer/jerlang/3.0.0/jerlang-3.0.0-all.jar
    java -jar jerlang-3.0.0-all.jar
    ```

The Maven and Gradle artifact is the thin library jar: the runtime and its own
classes, with `jzlib`, `jnr-posix`, `jline` and `commons-math3` declared as
normal dependencies. The runnable artifact is published under the `all`
classifier. It bundles those dependencies and has a `Main-Class` of
`org.jerlang.erts.Emulator`, so `java -jar` starts the Erlang shell with no
classpath setup. Use the `all` jar for a one-file launch and the thin artifact
as a dependency.

### Check what you downloaded

Central serves a detached signature and checksums next to every artifact, and
checking them against Central's own copies is what makes the check meaningful:

```sh
BASE=https://repo1.maven.org/maven2/io/github/slayyyyer/jerlang/3.0.0
curl -sO $BASE/jerlang-3.0.0-all.jar
curl -sO $BASE/jerlang-3.0.0-all.jar.asc
curl -s  $BASE/jerlang-3.0.0-all.jar.sha256

gpg --verify jerlang-3.0.0-all.jar.asc jerlang-3.0.0-all.jar
# gpg: Good signature from "Justin Baker (jerlang release signing)"
#      using RSA key 53566F66F97945DF654415F886F6991BB5D61373
```

## Compile and run a module

```erlang
%% hello.erl
-module(hello).
-export([greet/1]).

greet(Name) ->
    "hello, " ++ Name.
```

```sh
java -jar jerlang-3.0.0-all.jar compile -o build hello.erl
java -jar jerlang-3.0.0-all.jar -pa build
```

`compile` takes one or more source files and creates the output directory if it
does not exist:

| Option | Meaning |
|--------|---------|
| `-o DIR` | Write `.beam` files to `DIR` (default `.`) |
| `-I DIR` | Add `DIR` to the `-include` search path |
| `-pa DIR` | Add `DIR` as a library root for `-include_lib` and parse-transform beams |
| `-Dname[=value]` | Define a macro; without a value it is `true` |
| `-W` | Lint and print warnings (`-W0` disables, `-Werror` fails the build) |
| `+debug_info` | Accepted; line tables are already emitted, and transforms see the flag |
| `+warnings_as_errors` | Same as `-Werror` |
| `--` | Treat the remaining arguments as source files |

`-include("...")` resolves next to the source and then under each `-I`
directory. `-include_lib("app/include/file.hrl")` resolves `app` under each
`-pa` root, using the `app-version` directory layout OTP uses. `-Dname=value`
scans the value as Erlang tokens, so `-DLEVEL=2` defines an integer,
`-DNAME=worker` an atom, and `-DFLAG` the atom `true`, matching `erlc`.

`-compile({parse_transform, Module}).` and
`-compile([{parse_transform, Module}]).` run a transform after parsing and
before linting. The module must export `parse_transform/2` and is loaded from a
`-pa` root, the working directory, or the ordinary code path. It runs in a
started JErlang VM and receives the forms plus OTP-shaped options:
`debug_info`, `warnings_as_errors`, `{i, Dir}` and `{d, Name, Value}`. Modules
beside the transform are loadable from `-pa` too, so a transform may call a
helper compiled into the same directory. The transform may itself have been
compiled by JErlang.

A transform that spawns an Erlang process has a 30 second default bound. The
Java API can change it with `parseTransformTimeoutMillis`; zero disables the
bound. A timeout is a compile error and does not write a beam. Parse transforms
are trusted compile-time code, not a sandbox.

The same compiler is available as a Java API when the command line is not the
right surface:

```java
Compiler.Options options = Compiler.Options.builder()
    .outputDirectory(Paths.get("build"))
    .includePath("include")
    .define("DEBUG", null)
    .warnings(true)
    .build();
Compiler.Result result = Compiler.compileFile(Paths.get("hello.erl"), options);
if (!result.success()) {
    for (Diagnostic diagnostic : result.diagnostics()) {
        System.err.println(diagnostic.format());
    }
}
```

`Compiler.compile` returns the beam bytes without writing a file.

```erlang
1> hello:greet("erlang").
"hello, erlang"
```

At run time, `-pa` prepends a directory to the code path; `-pz` appends, and
`ERL_LIBS` works the way it does in OTP. The compile command's `-pa` is an
`-include_lib` library root, not a run-time code path. The module loader also looks in the working
directory, so a `.beam` next to where the JVM was started needs no flag.

The module search path is process-wide state. `Node.start` resets it and the
module registry, then adds the directories in `NodeOptions`; an embedding does
not call `CodePaths.reset()` itself. Close one node before starting the next.

## Embed the runtime in Java

`org.jerlang.api.Node` owns the runtime: it starts the schedulers, applies the
options and, on `close()`, drains the Java object handle table. It implements
`AutoCloseable`, so the normal shape is try-with-resources.

```java
import org.jerlang.api.Bridge;
import org.jerlang.api.Node;
import org.jerlang.type.Integer;
import org.jerlang.type.Term;

try (Node node = Node.start()) {
    Term result = node.apply("hello", "greet", new org.jerlang.type.Str("java"));
    Object greeting = Bridge.toJava(result);      // "hello, java"
    System.out.println(greeting);

    // An Erlang fun is callable directly, too.
    Term out = node.apply(anErlangFun, Integer.of(41));
}
```

Three things matter for a real application:

- **`Node` is the lifecycle.** Creating it starts schedulers; `close()` stops
  them and releases handles. Do not create one per request.
- **Reflection is opt-in.** `Node.start()` registers no `java` module, so Erlang
  code cannot reach arbitrary JVM classes. Ask for it explicitly:

  ```java
  Node node = Node.start(NodeOptions.builder().reflection(true).build());
  ```

  With it, Erlang code can call Java directly:

  ```erlang
  java:call_static("com.example.Pricing", "quote", [Sku, Quantity]).
  ```

  Bind Java code into Erlang by name instead of by reflection with
  `registerFunction`, which makes a lambda callable as a normal module
  function:

  ```java
  node.registerFunction("pricing", "quote", 2, args -> quote(args[0], args[1]));
  ```

- **Object handles are bounded and explicit.** A Java object that has no value
  mapping comes back to Erlang as a handle; `java:release/1` drops it and
  `Node.close()` drains the rest. The cap is `NodeOptions.maxHandles`, 100 000
  by default, and exceeding it raises `system_limit`. There is no finalizer, so
  an unreleased handle keeps its Java object alive for the life of the node.

The full value mapping — which Erlang term becomes which Java type, and back —
is in [Java interoperability](java-interop.md). It is the compatibility
contract, and `mvn verify` fails on a change to it without a version bump.

## Use OTP's own library

A `gen_server` is not reimplemented here. Compile the callback module, put
OTP's `stdlib` and `kernel` on the code path, and call it:

```java
try (Node node = Node.start(NodeOptions.builder()
        .otpRoot(Path.of(otpRoot)).build())) {
    Term server = node.apply("counter", "start").toTuple().element(2);
    node.apply("counter", "increment", server);
    System.out.println(Bridge.toJava(node.apply("counter", "value", server)));
}
```

where `otpRoot` is `code:root_dir()` from the installation. `otpRoot` adds the
`kernel` and `stdlib` ebins for you, and the working directory remains the
default code path entry. The complete program is the
[Java calls Erlang](examples/gen-server.md) example.

You can also start from the other side and let an OTP node call this one; see
[Distribution](distribution.md) for the cookie, the node name and the
`net_adm:ping/1` check.

## Build from source

```sh
mvn test            # the unit tests need no Erlang installation
mvn package
java -jar target/jerlang-<version>-all.jar
```

The build targets Java 21 with `--release`, so an accidental use of a newer
JDK's API is a compile error rather than a `NoSuchMethodError` at run time. CI
runs the suite on JDK 25.

Opt-in suites, all of which need a machine or a peer rather than the build:

```sh
mvn test -Dtest=OtpInteropTest -Djerlang.dist.otp=true    # needs a live OTP 29 peer
mvn -Dtest=BenchTest -Djerlang.bench=true test            # measures the machine
mvn -Dtest=DistributionBenchmarkTest -Djerlang.bench=true test  # distribution codec
mvn jacoco:prepare-agent test jacoco:report
mvn pitest:mutationCoverage
```

## Next

- [Java calls Erlang](examples/gen-server.md) and
  [Erlang calls Java](examples/java-service.md) — the two directions, end to end.
- [Distribution](distribution.md) — clustering with OTP.
- [Operating a node](operations.md) — metrics, logging and graceful shutdown.
- [Benchmarks](benchmarks.md) — what it costs against BEAM, measured on the same
  machine rather than asserted.
