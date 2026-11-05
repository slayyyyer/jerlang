# Maven embedding without Erlang

This is the smallest Maven service that keeps Erlang beside Java: `.erl` sources
under `src/main/erl`, compiled by JErlang during the build, and a Java `main`
that starts a node and calls the module in the same process. A JDK is the only
toolchain required.

```sh
cd examples/maven-embed
mvn verify
```

The test checks that `target/ebin/greeter.beam` was produced by this build and
then calls it through `org.jerlang.api.Node`. To see the demo output:

```sh
mvn -q compile exec:java
```

```text
hello, Java
6 * 7 = 42
```

## The build step

`pom.xml` has one runtime dependency, `io.github.slayyyyer:jerlang`, and runs
the compiler's own main class during `generate-resources`:

```xml
<executable>java</executable>
<arguments>
  <argument>-classpath</argument>
  <classpath/>
  <argument>org.jerlang.compiler.CompilerCommand</argument>
  <argument>-o</argument>
  <argument>${project.build.directory}/ebin</argument>
  <argument>${project.basedir}/src/main/erl/greeter.erl</argument>
</arguments>
```

That is the same compiler main class the runnable jar's `jerlang compile`
command invokes; it just runs from Maven's dependency classpath. It never calls
`erlc`, so the build does not depend on an Erlang installation. The CI job for
this example sets `ERLC` and `ERL` to nonexistent paths to make that explicit.

The test also rejects a beam older than its source, so a checkout that skipped
the compiler cannot pass on a committed or stale file.

## The Java side

```java
CodePaths.reset();
ModuleRegistry.reset();
CodePaths.addPatha(CodePaths.normalizePath(ebin));

try (Node node = Node.start()) {
    byte[] bytes = (byte[]) Bridge.toJava(node.apply("greeter", "greet",
        Bridge.toTerm("Java".getBytes(StandardCharsets.UTF_8))));
    System.out.println(new String(bytes, StandardCharsets.UTF_8));
}
```

The resets are needed because the module loader and registry are process-wide in
2.0. E3 in the [embedding plan](../../docs/embedding.md) is the milestone that
turns this lifecycle into a documented API rather than a copied recipe.

## Files

| File | What it is |
| --- | --- |
| `pom.xml` | One dependency, the JErlang compile execution, and the test setup. |
| `src/main/erl/greeter.erl` | The Erlang module; compiled by JErlang, not `erlc`. |
| `src/main/java/example/EmbedDemo.java` | Starts the node and calls the module. |
| `src/test/java/example/EmbedDemoTest.java` | Proves the beam was built and callable. |

## What this does not need

`greeter` uses only the built-in runtime, so this example needs no Erlang
installation at build or run time. If the module is a `gen_server` or uses
another OTP behaviour, add OTP's `stdlib` and `kernel` to the code path and
install Erlang/OTP; that boundary is covered in the
[embedding plan](../../docs/embedding.md).
